# Compile with the same JDK discovery and Java 17 target used by run.ps1.
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly
if (-not $?) { exit 1 }
