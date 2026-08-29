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
    [int]$LaunchIterations = 10,
    [ValidateRange(1, 10)]
    [int]$TraversalIterations = 5,
    [double[]]$PendingLatencyMs = @(),
    [switch]$SkipPendingFeedback,
    [switch]$SelfTest
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
        [Parameter(Mandatory = $true)][double[]]$Values,
        [Parameter(Mandatory = $true)][ValidateRange(0.0, 1.0)][double]$Percentile
    )
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $index = [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
    return $sorted[$index]
}

function Get-Median {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $middle = [Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

function Get-PendingFeedbackGate {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][double[]]$PendingValues,
        [AllowNull()][object]$PendingResults,
        [bool]$Waived = $false
    )
    if ($Waived) {
        return [pscustomobject]@{
            Status = "WAIVED"
            Failure = $null
            Message = "Pending-feedback latency gate: WAIVED by explicit user approval via -SkipPendingFeedback; no passing latency result is claimed."
        }
    }
    if ($PendingValues.Count -ne 10) {
        $failure = "exactly ten pending-feedback latency samples are required; received $($PendingValues.Count)"
        return [pscustomobject]@{ Status = "FAIL"; Failure = $failure; Message = "Pending-feedback latency gate: FAIL - $failure." }
    }
    if ($null -eq $PendingResults) {
        $failure = "pending-feedback statistics could not be calculated"
        return [pscustomobject]@{ Status = "FAIL"; Failure = $failure; Message = "Pending-feedback latency gate: FAIL - $failure." }
    }
    if ($PendingResults.Maximum -gt 100) {
        $failure = "pending-feedback maximum {0:N1} ms exceeded 100 ms" -f $PendingResults.Maximum
        return [pscustomobject]@{ Status = "FAIL"; Failure = $failure; Message = "Pending-feedback latency gate: FAIL - $failure." }
    }
    return [pscustomobject]@{
        Status = "PASS"
        Failure = $null
        Message = "Pending-feedback latency gate: PASS - all ten samples were at most 100 ms."
    }
}

function Convert-GfxInfoToFrameOverruns {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [string[]]$Lines
    )

    $headers = $null
    $headerMap = @{}
    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("Flags,")) {
            $headers = $trimmed.TrimEnd(",").Split(",")
            $headerMap = @{}
            for ($index = 0; $index -lt $headers.Count; $index++) {
                $headerMap[$headers[$index]] = $index
            }
            continue
        }
        if ($null -eq $headers -or $trimmed -notmatch "^\d+,") { continue }

        $values = $trimmed.TrimEnd(",").Split(",")
        if ($values.Count -lt $headers.Count) { continue }
        if (-not $headerMap.ContainsKey("Flags") -or
            -not $headerMap.ContainsKey("IntendedVsync") -or
            -not $headerMap.ContainsKey("FrameCompleted")) {
            continue
        }

        $flags = [long]::Parse($values[$headerMap["Flags"]], [Globalization.CultureInfo]::InvariantCulture)
        if ($flags -ne 0) { continue }
        $intendedVsync = [double]::Parse($values[$headerMap["IntendedVsync"]], [Globalization.CultureInfo]::InvariantCulture)
        $frameCompleted = [double]::Parse($values[$headerMap["FrameCompleted"]], [Globalization.CultureInfo]::InvariantCulture)
        $frameDeadline = $null
        if ($headerMap.ContainsKey("FrameDeadline")) {
            $candidate = [double]::Parse($values[$headerMap["FrameDeadline"]], [Globalization.CultureInfo]::InvariantCulture)
            if ($candidate -gt 0) { $frameDeadline = $candidate }
        }
        $frameInterval = $null
        if ($headerMap.ContainsKey("FrameInterval")) {
            $candidate = [double]::Parse($values[$headerMap["FrameInterval"]], [Globalization.CultureInfo]::InvariantCulture)
            if ($candidate -gt 0) { $frameInterval = $candidate }
        }
        if ($intendedVsync -gt 0 -and $frameCompleted -gt 0) {
            $rows.Add([pscustomobject]@{
                IntendedVsync = $intendedVsync
                FrameDeadline = $frameDeadline
                FrameInterval = $frameInterval
                FrameCompleted = $frameCompleted
            })
        }
    }

    $intervalCandidates = @($rows | Where-Object { $null -ne $_.FrameInterval } | ForEach-Object { [double]$_.FrameInterval })
    if ($intervalCandidates.Count -eq 0 -and $rows.Count -gt 1) {
        $orderedVsyncs = @($rows | ForEach-Object { [double]$_.IntendedVsync } | Sort-Object -Unique)
        $intervalCandidates = for ($index = 1; $index -lt $orderedVsyncs.Count; $index++) {
            $delta = $orderedVsyncs[$index] - $orderedVsyncs[$index - 1]
            if ($delta -gt 0) { $delta }
        }
    }
    $estimatedInterval = if ($intervalCandidates.Count -gt 0) {
        Get-Percentile -Values $intervalCandidates -Percentile 0.5
    } else {
        16666667.0
    }

    $usedEstimatedDeadline = $false
    $overruns = [System.Collections.Generic.List[double]]::new()
    foreach ($row in $rows) {
        $deadline = $row.FrameDeadline
        if ($null -eq $deadline) {
            $interval = if ($null -ne $row.FrameInterval) { $row.FrameInterval } else { $estimatedInterval }
            $deadline = $row.IntendedVsync + $interval
            $usedEstimatedDeadline = $true
        }
        $overruns.Add(($row.FrameCompleted - $deadline) / 1000000.0)
    }
    return [pscustomobject]@{
        Values = $overruns.ToArray()
        UsedEstimatedDeadline = $usedEstimatedDeadline
    }
}

function Invoke-SelfTest {
    $modern = Convert-GfxInfoToFrameOverruns -Lines @(
        "Flags,IntendedVsync,FrameDeadline,FrameInterval,FrameCompleted,",
        "0,1000000000,1016000000,16000000,1015000000,",
        "0,1016000000,1032000000,16000000,1034000000,"
    )
    if ($modern.Values.Count -ne 2 -or
        [Math]::Round($modern.Values[0], 3) -ne -1.0 -or
        [Math]::Round($modern.Values[1], 3) -ne 2.0 -or
        $modern.UsedEstimatedDeadline) {
        throw "Modern gfxinfo parser self-test failed."
    }

    $legacy = Convert-GfxInfoToFrameOverruns -Lines @(
        "Flags,IntendedVsync,FrameCompleted,",
        "0,1000000000,1017000000,",
        "0,1016000000,1033000000,"
    )
    if ($legacy.Values.Count -ne 2 -or
        [Math]::Round($legacy.Values[0], 3) -ne 1.0 -or
        [Math]::Round($legacy.Values[1], 3) -ne 1.0 -or
        -not $legacy.UsedEstimatedDeadline) {
        throw "Legacy gfxinfo parser self-test failed."
    }

    $blankLines = Convert-GfxInfoToFrameOverruns -Lines @("", " ", "No framestats available")
    if ($blankLines.Values.Count -ne 0 -or $blankLines.UsedEstimatedDeadline) {
        throw "Blank-line gfxinfo parser self-test failed."
    }

    $missing = Convert-GfxInfoToFrameOverruns -Lines @()
    if ($missing.Values.Count -ne 0 -or $missing.UsedEstimatedDeadline) {
        throw "Missing gfxinfo parser self-test failed."
    }

    $percentile = Get-Percentile -Values @(-5.0, -1.0, 2.0, 7.0, 11.0) -Percentile 0.95
    if ($percentile -ne 11.0) { throw "Percentile self-test failed." }

    $median = Get-Median -Values @(1.0, 10.0, 2.0, 9.0, 3.0, 8.0, 4.0, 7.0, 5.0, 6.0)
    if ($median -ne 5.5) { throw "Median self-test failed." }

    $validFailures = @(Get-ReleaseValidationFailures `
        -ColdResults ([pscustomobject]@{ Count = 10; Median = 1500.0 }) `
        -FrameResults ([pscustomobject]@{ Count = 30; P95 = 0.0 }) `
        -PendingValues @(100.0, 90.0, 80.0, 70.0, 60.0, 50.0, 40.0, 30.0, 20.0, 10.0) `
        -PendingResults ([pscustomobject]@{ Maximum = 100.0 }))
    if ($validFailures.Count -ne 0) { throw "Passing release-gate self-test failed." }

    $invalidFailures = @(Get-ReleaseValidationFailures `
        -ColdResults $null `
        -FrameResults ([pscustomobject]@{ Count = 29; P95 = 0.1 }) `
        -PendingValues @(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0) `
        -PendingResults ([pscustomobject]@{ Maximum = 9.0 }))
    if ($invalidFailures.Count -ne 4) { throw "Fail-closed release-gate self-test failed." }

    $missingPendingFailures = @(Get-ReleaseValidationFailures `
        -ColdResults ([pscustomobject]@{ Count = 10; Median = 1500.0 }) `
        -FrameResults ([pscustomobject]@{ Count = 30; P95 = 0.0 }) `
        -PendingValues @() `
        -PendingResults $null)
    if ($missingPendingFailures.Count -ne 1 -or
        $missingPendingFailures[0] -ne "exactly ten pending-feedback latency samples are required; received 0") {
        throw "Missing pending-feedback release-gate self-test failed."
    }

    $waivedGate = Get-PendingFeedbackGate -PendingValues @() -PendingResults $null -Waived $true
    $waivedFailures = @(Get-ReleaseValidationFailures `
        -ColdResults ([pscustomobject]@{ Count = 10; Median = 1500.0 }) `
        -FrameResults ([pscustomobject]@{ Count = 30; P95 = 0.0 }) `
        -PendingValues @() `
        -PendingResults $null `
        -SkipPendingFeedback $true)
    if ($waivedGate.Status -ne "WAIVED" -or
        $waivedGate.Message -notmatch "WAIVED" -or
        $waivedGate.Message -match "\bPASS\b" -or
        $waivedFailures.Count -ne 0) {
        throw "Explicit pending-feedback waiver self-test failed."
    }
    Write-Host "Shield validation self-test passed (10 checks)."
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
    $median = Get-Median -Values $times.ToArray()
    $p95 = [int](Get-Percentile -Values $times.ToArray() -Percentile 0.95)
    $maximum = ($times | Measure-Object -Maximum).Maximum
    Write-Host ("{0} median: {1:N1} ms; P95: {2} ms; max: {3} ms." -f $Label, $median, $p95, $maximum)
    return [pscustomobject]@{ Count = $times.Count; Median = $median; P95 = $p95; Maximum = $maximum }
}

function Measure-FrameOverrun {
    Write-Host "`n30-tile D-pad traversal frame timing ($TraversalIterations runs):"
    Invoke-Adb -Arguments @("shell", "dumpsys", "gfxinfo", $PackageName, "reset") | Out-Null
    for ($iteration = 1; $iteration -le $TraversalIterations; $iteration++) {
        foreach ($key in @(
            "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN",
            "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN",
            "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_RIGHT",
            "KEYCODE_DPAD_UP", "KEYCODE_DPAD_UP", "KEYCODE_DPAD_UP", "KEYCODE_DPAD_UP",
            "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_LEFT"
        )) {
            Invoke-Adb -Arguments @("shell", "input", "keyevent", $key) | Out-Null
        }
    }
    Start-Sleep -Milliseconds 500
    $gfxInfo = Invoke-Adb -Arguments @("shell", "dumpsys", "gfxinfo", $PackageName, "framestats")
    $parsed = Convert-GfxInfoToFrameOverruns -Lines $gfxInfo
    if ($parsed.Values.Count -eq 0) {
        throw "No valid gfxinfo framestats records were captured for '$PackageName'. Confirm the configured dashboard is visible and that dumpsys gfxinfo reports framestats, then rerun."
    }
    if ($parsed.UsedEstimatedDeadline) {
        Write-Warning "FrameDeadline was unavailable; overrun used IntendedVsync plus the reported or estimated frame interval."
    }
    $p50 = Get-Percentile -Values $parsed.Values -Percentile 0.50
    $p90 = Get-Percentile -Values $parsed.Values -Percentile 0.90
    $p95 = Get-Percentile -Values $parsed.Values -Percentile 0.95
    $p99 = Get-Percentile -Values $parsed.Values -Percentile 0.99
    Write-Host ("Frame overrun ({0} frames): P50 {1:N2} ms; P90 {2:N2} ms; P95 {3:N2} ms; P99 {4:N2} ms." -f $parsed.Values.Count, $p50, $p90, $p95, $p99)
    return [pscustomobject]@{ Count = $parsed.Values.Count; P50 = $p50; P90 = $p90; P95 = $p95; P99 = $p99 }
}

function Measure-PendingLatencyEvidence {
    if ($PendingLatencyMs.Count -eq 0) { return $null }
    $invalidSamples = @($PendingLatencyMs | Where-Object {
        $_ -lt 0 -or [double]::IsNaN($_) -or [double]::IsInfinity($_)
    })
    if ($invalidSamples.Count -gt 0) {
        throw "Pending latency samples must be finite, non-negative milliseconds."
    }
    $median = Get-Median -Values $PendingLatencyMs
    $p95 = Get-Percentile -Values $PendingLatencyMs -Percentile 0.95
    $maximum = ($PendingLatencyMs | Measure-Object -Maximum).Maximum
    Write-Host ("Pending feedback ({0} samples): median {1:N1} ms; P95 {2:N1} ms; max {3:N1} ms." -f $PendingLatencyMs.Count, $median, $p95, $maximum)
    return [pscustomobject]@{ Count = $PendingLatencyMs.Count; Median = $median; P95 = $p95; Maximum = $maximum }
}

function Get-ReleaseValidationFailures {
    param(
        [AllowNull()][object]$ColdResults,
        [Parameter(Mandatory = $true)][object]$FrameResults,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][double[]]$PendingValues,
        [AllowNull()][object]$PendingResults,
        [bool]$SkipPendingFeedback = $false
    )
    $failures = [System.Collections.Generic.List[string]]::new()
    if ($null -eq $ColdResults) {
        $failures.Add("no cold-launch measurements were captured")
    } elseif ($ColdResults.Count -ne $LaunchIterations) {
        $failures.Add("expected $LaunchIterations cold-launch measurements; captured $($ColdResults.Count)")
    } elseif ($ColdResults.Median -gt 1500) {
        $failures.Add(("cold launch median {0:N1} ms exceeded 1500 ms" -f $ColdResults.Median))
    }
    if ($FrameResults.P95 -gt 0) {
        $failures.Add(("frame-overrun P95 {0:N2} ms exceeded 0 ms" -f $FrameResults.P95))
    }
    if ($FrameResults.Count -lt 30) {
        $failures.Add("at least 30 valid frame records are required; captured $($FrameResults.Count)")
    }
    $pendingGate = Get-PendingFeedbackGate `
        -PendingValues $PendingValues `
        -PendingResults $PendingResults `
        -Waived $SkipPendingFeedback
    if ($pendingGate.Status -eq "FAIL") {
        $failures.Add($pendingGate.Failure)
    }
    return $failures.ToArray()
}

if ($SelfTest) {
    Invoke-SelfTest
    return
}

if ($SkipPendingFeedback -and $BuildVariant -ne "Release") {
    throw "-SkipPendingFeedback is an explicit release-gate waiver and requires -BuildVariant Release."
}
if ($SkipPendingFeedback -and $PendingLatencyMs.Count -gt 0) {
    throw "Use either -SkipPendingFeedback or -PendingLatencyMs, not both."
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

$coldResults = Measure-Launches -Label "Cold" -Cold $true
$warmResults = Measure-Launches -Label "Warm" -Cold $false
$frameResults = Measure-FrameOverrun
$pendingResults = Measure-PendingLatencyEvidence

if ($BuildVariant -eq "Release") {
    Write-Host "Release target: cold median <= 1500 ms to cached focused control."
} elseif ($BuildVariant -eq "Debug") {
    Write-Host "Debug timing is informational only. Benchmark the signed, minified APK with -BuildVariant Release before distribution."
}

if ($BuildVariant -eq "Release") {
    Write-Host "Release target: frame-overrun P95 <= 0 ms during 30-tile traversal."
    $pendingGate = Get-PendingFeedbackGate `
        -PendingValues $PendingLatencyMs `
        -PendingResults $pendingResults `
        -Waived ([bool]$SkipPendingFeedback)
    if ($pendingGate.Status -eq "WAIVED") {
        Write-Host $pendingGate.Message -ForegroundColor Yellow
    } else {
        Write-Host $pendingGate.Message
    }
    $releaseFailures = @(Get-ReleaseValidationFailures `
        -ColdResults $coldResults `
        -FrameResults $frameResults `
        -PendingValues $PendingLatencyMs `
        -PendingResults $pendingResults `
        -SkipPendingFeedback ([bool]$SkipPendingFeedback))
} else {
    $releaseFailures = @()
}

Write-Host "`nRendering diagnostics (capture this output with the release checklist):"
Invoke-Adb -Arguments @("shell", "dumpsys", "gfxinfo", $PackageName)

if ($releaseFailures.Count -gt 0) {
    throw "Release validation failed: $($releaseFailures -join '; ')."
}

Write-Host "`nManual checks still required: remote-only setup, Settings-button mapping, valid/invalid TLS, live Home Assistant updates, alarm/cover confirmations, Android TV Home opt-in, and visual focus/layout verification."
