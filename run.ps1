# ThreadStorm one-file Windows launcher (PowerShell 5.1 / 7+).
# Place beside Main.java. Examples:
#   .\run.ps1                  # terminal dashboard (default)
#   .\run.ps1 -Dashboard       # terminal dashboard (same as default)
#   .\run.ps1 -Test            # build and run the bundled tests
#   .\run.ps1 -NoBuild         # reuse existing build output
#   .\run.ps1 -Cli --workers 4 --capacity 128
param(
    [switch]$Dashboard,
    [switch]$Cli,
    [switch]$Test,
    [switch]$NoBuild,
    [switch]$BuildOnly,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AppArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }
$root = (Resolve-Path -LiteralPath $root).Path
if (-not (Test-Path -LiteralPath (Join-Path $root 'Main.java'))) {
    throw "Place run.ps1 in the ThreadStorm folder containing Main.java. Folder: $root"
}

function Get-Jdk {
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $cmd.Source)))
    }
    foreach ($base in @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft', 'C:\Program Files\Amazon Corretto', 'C:\Program Files\Zulu')) {
        if (Test-Path -LiteralPath $base) {
            Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue | ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    foreach ($jdkPath in ($candidates | Select-Object -Unique)) {
        $compiler = Join-Path $jdkPath 'bin\javac.exe'
        $runtime = Join-Path $jdkPath 'bin\java.exe'
        if (-not ((Test-Path -LiteralPath $compiler) -and (Test-Path -LiteralPath $runtime))) { continue }
        $v = (& $compiler -version 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $v -notmatch 'javac\s+(\d+)(?:\.|\s|$)') { continue }
        if ([int]$Matches[1] -lt 17) { continue }
        return @{ Home = $jdkPath; Javac = $compiler; Java = $runtime; Version = $v }
    }
    throw "No JDK 17+ found. Install JDK 17 or newer and set JAVA_HOME to the JDK directory (not the bin directory)."
}

$jdk = Get-Jdk
$env:JAVA_HOME = $jdk.Home
$env:Path = "$(Join-Path $jdk.Home 'bin');$env:Path"
Write-Host "[ThreadStorm] Using $($jdk.Version) from $($jdk.Home)" -ForegroundColor Cyan

$outDir = Join-Path $root 'out'
if (-not $NoBuild) {
    $dirs = @('core', 'task', 'metrics', 'cli', 'benchmarks', 'tests')
    $sources = @((Join-Path $root 'Main.java'))
    foreach ($dir in $dirs) {
        $path = Join-Path $root $dir
        if (Test-Path -LiteralPath $path) {
            $sources += @(Get-ChildItem -LiteralPath $path -Filter '*.java' -File -Recurse | ForEach-Object { $_.FullName })
        }
    }
    # Compile to a temporary directory, preserving the previous build if compilation fails.
    $tempOut = Join-Path $root 'out.run.tmp'
    if (Test-Path -LiteralPath $tempOut) { Remove-Item -LiteralPath $tempOut -Recurse -Force }
    New-Item -ItemType Directory -Path $tempOut | Out-Null
    Write-Host "[ThreadStorm] Compiling $($sources.Count) source files for Java 17..." -ForegroundColor Cyan
    try {
        & $jdk.Javac --release 17 -encoding UTF-8 -d $tempOut @sources
        if ($LASTEXITCODE -ne 0) { throw "Java compilation failed (exit $LASTEXITCODE)." }
        if (Test-Path -LiteralPath $outDir) { Remove-Item -LiteralPath $outDir -Recurse -Force }
        Move-Item -LiteralPath $tempOut -Destination $outDir
        Write-Host '[ThreadStorm] Build successful.' -ForegroundColor Green
    } finally {
        if (Test-Path -LiteralPath $tempOut) { Remove-Item -LiteralPath $tempOut -Recurse -Force }
    }
} elseif (-not (Test-Path -LiteralPath (Join-Path $outDir 'Main.class'))) {
    throw 'No compiled Main.class exists. Run without -NoBuild first.'
}

if ($BuildOnly) { return }

Push-Location $root
try {
    if ($Test) {
        $tests = @(
            'tests.PrioritySchedulingTest', 'tests.FaultToleranceTest',
            'tests.ObservabilityTest', 'ThreadStormStressTest',
            'tests.BenchmarkSmokeTest', 'tests.TaskCoordinationTest',
            'tests.LifecycleHardeningTest', 'tests.CancellationHardeningTest',
            'tests.TuiEngineIntegrationTest', 'cli.TerminalInputSmokeTest'
        )
        foreach ($testName in $tests) {
            Write-Host "`n[ThreadStorm] Testing $testName" -ForegroundColor Cyan
            & $jdk.Java -cp $outDir $testName
            if ($LASTEXITCODE -ne 0) { throw "Test failed: $testName (exit $LASTEXITCODE)" }
        }
        Write-Host "`n[ThreadStorm] All $($tests.Count) tests passed." -ForegroundColor Green
    } elseif (-not $Cli) {
        # Use UTF-8 console output for Unicode mode; ASCII remains the TUI default on Windows.
        if (Get-Command chcp.com -ErrorAction SilentlyContinue) { & chcp.com 65001 | Out-Null }
        & $jdk.Java '-Dfile.encoding=UTF-8' -cp $outDir Main @AppArgs
        if ($LASTEXITCODE -ne 0) { throw "Dashboard exited with code $LASTEXITCODE" }
    } else {
        Write-Host '[ThreadStorm] Launching text CLI. Type help for commands; exit to quit.' -ForegroundColor Green
        & $jdk.Java -cp $outDir cli.ThreadStormCli @AppArgs
        if ($LASTEXITCODE -ne 0) { throw "CLI exited with code $LASTEXITCODE" }
    }
} finally {
    Pop-Location
}
