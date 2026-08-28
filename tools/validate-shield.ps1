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

function Get-Percentile {
    param(
        [Parameter(Mandatory = $true)][int[]]$Values,
        [Parameter(Mandatory = $true)][ValidateRange(0.0, 1.0)][double]$Percentile
    )
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $index = [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
    return $sorted[$index]
}

function Measure-Launches {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][bool]$Cold
    )
    Write-Host "`n$Label launch timing ($LaunchIterations runs):"
    $times = [System.Collections.Generic.List[int]]::new()
    if (-not $Cold) {
        Invoke-Adb -Arguments @("shell", "am", "start-activity", "-W", "-n", $activityName) | Out-Null
    }
    for ($index = 1; $index -le $LaunchIterations; $index++) {
        if ($Cold) {
            Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName)
        } else {
            Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_HOME")
        }
        $launch = Invoke-Adb -Arguments @("shell", "am", "start-activity", "-W", "-n", $activityName)
        $totalTime = $launch | Select-String -Pattern "^TotalTime:\s+(\d+)" | ForEach-Object { [int]$_.Matches[0].Groups[1].Value }
        if ($totalTime.Count -eq 1) {
            $times.Add($totalTime[0])
            Write-Host "  $index : $($totalTime[0]) ms"
        } else {
            Write-Warning "Could not determine launch time for run $index."
        }
    }
    if ($times.Count -eq 0) { return $null }
    $median = Get-Percentile -Values $times.ToArray() -Percentile 0.5
    $p95 = Get-Percentile -Values $times.ToArray() -Percentile 0.95
    $maximum = ($times | Measure-Object -Maximum).Maximum
    Write-Host "$Label median: $median ms; p95: $p95 ms; max: $maximum ms."
    return [pscustomobject]@{ Median = $median; P95 = $p95; Maximum = $maximum }
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
    throw "Release validation requires a physical NVIDIA Shield. Use the Macrobenchmark suite for emulator trend data."
}

if ($Install) {
    $resolvedApk = Resolve-Path -LiteralPath $ApkPath
    Write-Host "Installing $resolvedApk"
    Invoke-Adb -Arguments @("install", "-r", $resolvedApk.Path)
}

$releaseLaunchTargetMissed = $false
$coldResults = Measure-Launches -Label "Cold" -Cold $true
$warmResults = Measure-Launches -Label "Warm" -Cold $false

if ($BuildVariant -eq "Release" -and $null -ne $coldResults) {
    Write-Host "Release target: cold median <= 1500 ms to cached focused control."
    if ($coldResults.Median -gt 1500) {
        Write-Warning "Release launch target missed. Investigate before distribution."
        $releaseLaunchTargetMissed = $true
    }
} elseif ($BuildVariant -eq "Debug") {
    Write-Host "Debug timing is informational only. Benchmark the signed, minified APK with -BuildVariant Release before distribution."
}

Write-Host "\nRendering diagnostics (capture this output with the release checklist):"
Invoke-Adb -Arguments @("shell", "dumpsys", "gfxinfo", $PackageName)

if ($releaseLaunchTargetMissed) {
    throw "Release launch target missed. Investigate before distribution."
}

Write-Host "\nManual checks still required: remote-only setup, Settings-button mapping, valid/invalid TLS, live Home Assistant updates, alarm/cover confirmations, Android TV Home opt-in, and command-feedback timing."
