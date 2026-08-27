[CmdletBinding()]
param(
    [string]$AdbPath = "adb",
    [string]$Serial,
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk",
    [string]$PackageName = "dev.haquickaccess.tv.debug",
    [string]$ActivityName = "dev.haquickaccess.tv.MainActivity",
    [ValidateSet("Debug", "Release")]
    [string]$BuildVariant = "Debug",
    [switch]$Install,
    [ValidateRange(1, 20)]
    [int]$LaunchIterations = 10
)

$ErrorActionPreference = "Stop"
$activityName = "$PackageName/$ActivityName"
$target = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $target = @("-s", $Serial)
}

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $AdbPath @target @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $($Arguments -join ' ')"
    }
}

$devices = Invoke-Adb -Arguments @("devices")
$connected = $devices | Select-String -Pattern "\sdevice$"
if ($connected.Count -eq 0) {
    throw "No authorized Android TV device was found. Connect the Shield with USB debugging enabled, then rerun this script."
}
if ([string]::IsNullOrWhiteSpace($Serial) -and $connected.Count -gt 1) {
    throw "More than one ADB device is connected. Pass -Serial to choose the Shield."
}

$model = (Invoke-Adb -Arguments @("shell", "getprop", "ro.product.model") | Out-String).Trim()
Write-Host "Target: $model"
if ($model -notmatch "SHIELD|NVIDIA") {
    Write-Warning "This does not identify as an NVIDIA Shield. Continue only if this is the intended Android TV device."
}

if ($Install) {
    $resolvedApk = Resolve-Path -LiteralPath $ApkPath
    Write-Host "Installing $resolvedApk"
    Invoke-Adb -Arguments @("install", "-r", $resolvedApk.Path)
}

Write-Host "\nLaunch timing ($LaunchIterations cold starts):"
$times = [System.Collections.Generic.List[int]]::new()
$releaseLaunchTargetMissed = $false
for ($index = 1; $index -le $LaunchIterations; $index++) {
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName)
    $launch = Invoke-Adb -Arguments @("shell", "am", "start-activity", "-W", "-n", $activityName)
    $totalTime = $launch | Select-String -Pattern "^TotalTime:\s+(\d+)" | ForEach-Object { [int]$_.Matches[0].Groups[1].Value }
    if ($totalTime.Count -eq 1) {
        $times.Add($totalTime[0])
        Write-Host "  $index : $($totalTime[0]) ms"
    } else {
        Write-Warning "Could not determine launch time for run $index."
    }
}

if ($times.Count -gt 0) {
    $average = [math]::Round(($times | Measure-Object -Average).Average, 1)
    $maximum = ($times | Measure-Object -Maximum).Maximum
    if ($BuildVariant -eq "Release") {
        Write-Host "Average: $average ms; max: $maximum ms; release target: <= 1500 ms to cached focused control."
        if ($average -gt 1500) {
            Write-Warning "Release launch target missed. Investigate before distribution."
            $releaseLaunchTargetMissed = $true
        }
    } else {
        Write-Host "Average: $average ms; max: $maximum ms; debug timing is informational only."
        Write-Host "Benchmark the signed, minified APK with -BuildVariant Release before distribution."
    }
}

Write-Host "\nRendering diagnostics (capture this output with the release checklist):"
Invoke-Adb -Arguments @("shell", "dumpsys", "gfxinfo", $PackageName)

if ($releaseLaunchTargetMissed) {
    throw "Release launch target missed. Investigate before distribution."
}

Write-Host "\nManual checks still required: remote-only setup, Settings-button mapping, valid/invalid TLS, live Home Assistant updates, alarm/cover confirmations, Android TV Home opt-in, and command-feedback timing."
