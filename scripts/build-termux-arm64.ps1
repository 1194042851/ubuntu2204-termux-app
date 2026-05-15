$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$env:ANDROID_HOME = 'E:\study\android-sdk-termux'
$env:ANDROID_SDK_ROOT = 'E:\study\android-sdk-termux'
$env:JAVA_HOME = 'e:\dev\jdk-17.0.18+8'
$env:TERMUX_PACKAGE_VARIANT = 'apt-android-7'
$env:TERMUX_TARGET_ABI = 'arm64-v8a'
$env:TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = '1'

& "$repoRoot\gradlew.bat" clean app:assembleDebug

$apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\ubuntu2204_apt-android-7-debug_arm64-v8a.apk'
if (-not (Test-Path $apkPath)) {
    throw "APK not found: $apkPath"
}

Write-Host "Built APK: $apkPath"
