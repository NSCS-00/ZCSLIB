# ZCSLIB Build Script (Windows)
# BUILDID System v2.0 - DLZstudio
# 
# This is the ONLY official entry point for building ZCSLIB.
# DO NOT invoke Gradle directly for release/test artifacts.

param(
    [switch]$dryRun = $false
)

$ErrorActionPreference = "Stop"
$buildIdFile = Join-Path $PSScriptRoot "BUILDID.txt"

# ==================== Project Metadata ====================
$projectName    = "ZCSLIB"
$semanticVersion = "0.2.0"
$targetOS       = "windows"
$targetArch     = "amd64"

# Java & Gradle paths
$JAVA_HOME  = "D:\Java\jdk-21"
$GRADLE_HOME = "D:\GRADLE\gradle-8.14.3-bin\bin"

# ==================== BUILDID Counter ====================

# Read current BUILDID
$currentId = Get-Content $buildIdFile -Raw
$currentId = $currentId.Trim()
$newId = [int]::Parse($currentId) + 1
$newIdStr = $newId.ToString("D8")

# Construct identifier
$buildIdentifier = "BUILD.$newIdStr"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host " ZCSLIB BUILDID System v2.0" -ForegroundColor Cyan
Write-Host " Project: $projectName v$semanticVersion" -ForegroundColor Gray
Write-Host " Target:  $targetOS / $targetArch" -ForegroundColor Gray
Write-Host " Previous: BUILD.$currentId" -ForegroundColor Gray
Write-Host " Current:  $buildIdentifier" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Cyan

if ($dryRun) {
    Write-Host "DRY RUN - BUILDID incremented but build skipped" -ForegroundColor Yellow
    Set-Content -Path $buildIdFile -Value $newIdStr -NoNewline
    exit 0
}

# Increment and persist
Set-Content -Path $buildIdFile -Value $newIdStr -NoNewline

# ==================== Execute Gradle Build ====================

$projectRoot = Join-Path $PSScriptRoot "..\.."
Set-Location $projectRoot

$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$env:PATH"

Write-Host "`n[1/2] Executing Gradle build..." -ForegroundColor Yellow
$gradleArgs = @(
    "build",
    "--no-daemon",
    "-Dorg.gradle.java.home=$JAVA_HOME",
    "-Dbuild.id=$buildIdentifier"
)

$gradleProc = Start-Process -FilePath "$GRADLE_HOME\gradle.bat" `
    -ArgumentList $gradleArgs `
    -NoNewWindow -Wait -PassThru

if ($gradleProc.ExitCode -ne 0) {
    Write-Host "`nBUILD FAILED! Reverting BUILDID..." -ForegroundColor Red
    Set-Content -Path $buildIdFile -Value $currentId -NoNewline
    exit 1
}

# ==================== Rename Output to Spec Convention ====================

$buildDir = Join-Path $projectRoot "build\libs"
$originalJar = Join-Path $buildDir "zcslib-$semanticVersion.jar"

# Target filename per BUILDID spec:
# {ProjectName}-{SemanticVersion}-{BuildIdentifier}_{OS}_{Arch}.{Ext}
$outputName = "$projectName-$semanticVersion-$buildIdentifier" + "_${targetOS}_${targetArch}.jar"
$outputJar = Join-Path $buildDir $outputName

Write-Host "`n[2/2] Renaming output per BUILDID spec..." -ForegroundColor Yellow

if (Test-Path $originalJar) {
    # Remove old named artifact if exists
    Get-ChildItem $buildDir -Filter "ZCSLIB-*-BUILD.*.jar" | Remove-Item -Force -ErrorAction SilentlyContinue
    Rename-Item -Path $originalJar -NewName $outputName
    Write-Host "       $outputName" -ForegroundColor Green
} else {
    Write-Host "WARNING: Expected JAR not found at $originalJar" -ForegroundColor Yellow
}

# ==================== Verify Manifest ====================

Write-Host "`nBuild succeeded: $buildIdentifier" -ForegroundColor Green
Write-Host "Artifact: $outputJar" -ForegroundColor Gray

# Quick manifest check
try {
    $jarPath = $outputJar
    if (Test-Path $jarPath) {
        Remove-Item "$env:TEMP\zcslib_manifest.tmp" -ErrorAction SilentlyContinue
        # Use jar tool to extract manifest for verification
        & "$JAVA_HOME\bin\jar.exe" xf $jarPath META-INF/MANIFEST.MF
        if (Test-Path "META-INF\MANIFEST.MF") {
            $manifestContent = Get-Content "META-INF\MANIFEST.MF" -Raw
            if ($manifestContent -match "Build-ID: $buildIdentifier") {
                Write-Host " Manifest: Build-ID embedded OK" -ForegroundColor Green
            }
            Remove-Item -Recurse -Force "META-INF" -ErrorAction SilentlyContinue
        }
    }
} catch {
    # Non-critical - manifest verification is optional
    Write-Host " Manifest verification skipped" -ForegroundColor Gray
}

exit 0
