# Build and execute the bundled test programs.
& (Join-Path $PSScriptRoot 'run.ps1') -Test
if (-not $?) { exit 1 }
