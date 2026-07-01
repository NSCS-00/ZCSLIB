# ZCSLIB Smoke Test Runner
# Usage: .\run-smoke.ps1 [-SkipNetwork]
param([switch]$SkipNetwork)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem "$root\build\libs\ZCSLIB-*-BUILD.*_windows_amd64.jar" | Select-Object -First 1

if (-not $jar) {
    Write-Host "ERROR: ZCSLIB JAR not found under build\libs\" -ForegroundColor Red
    exit 1
}

Write-Host "=== ZCSLIB Smoke Runner ===" -ForegroundColor Cyan
Write-Host "JAR:  $($jar.Name)" -ForegroundColor Cyan

# Create temp work dir (CrashHandler/AuditLogger write relative paths)
$workDir = Join-Path $env:TEMP "zcslib-smoke-$(Get-Random)"
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
Write-Host "CWD:  $workDir" -ForegroundColor DarkGray

# Phase 1: Compile
Write-Host "[1/3] Compiling SmokeTest..." -ForegroundColor Yellow
$compileOut = "$PSScriptRoot\out"
Remove-Item -Recurse -Force $compileOut -ErrorAction SilentlyContinue
$javacArgs = @(
    "--class-path", $jar.FullName,
    "-d", $compileOut,
    "$PSScriptRoot\SmokeTest.java"
)
$compile = & javac @javacArgs 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "COMPILE FAILED:" -ForegroundColor Red
    Write-Host ($compile -join "`n")
    exit 1
}
Write-Host "       Compile OK" -ForegroundColor Green

# Phase 2: Start echo server
$serverProc = $null
if (-not $SkipNetwork) {
    Write-Host "[2/3] Starting echo server on :19998..." -ForegroundColor Yellow
    $py = if (Get-Command python3 -ErrorAction SilentlyContinue) { 'python3' } else { 'python' }
    $serverProc = Start-Process $py -ArgumentList "$PSScriptRoot\server.py" -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2
    Write-Host "       Server PID: $($serverProc.Id)" -ForegroundColor Green
} else {
    Write-Host "[2/3] Network tests skipped (-SkipNetwork)" -ForegroundColor DarkYellow
}

# Phase 3: Run tests from workDir (CrashHandler uses CWD-relative paths)
Write-Host "[3/3] Running tests..." -ForegroundColor Yellow
Write-Host ""
$classPath = "$($jar.FullName);$compileOut"
Push-Location $workDir
try {
    $result = & java --class-path $classPath SmokeTest 2>&1
    $exitCode = $LASTEXITCODE
    Write-Host ($result -join "`n")
} finally {
    Pop-Location
}

# Cleanup
if ($serverProc) {
    Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue
    Write-Host ""
    Write-Host "Echo server stopped." -ForegroundColor DarkGray
}
Remove-Item -Recurse -Force $compileOut -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $workDir -ErrorAction SilentlyContinue

exit $exitCode
