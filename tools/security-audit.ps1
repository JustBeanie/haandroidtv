[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipDependencyScan
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

$bundledJdk = Get-ChildItem '.tooling\jdk17' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $bundledJdk -and (-not $env:JAVA_HOME -or $env:JAVA_HOME -match 'jre-8|jdk-8')) {
    $env:JAVA_HOME = $bundledJdk.FullName
    $env:Path = "$($env:JAVA_HOME)\bin;$env:Path"
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-local' }
if (-not $env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = Join-Path $projectRoot '.android-local' }
if ($env:ANDROID_SDK_HOME -and $env:ANDROID_USER_HOME -and $env:ANDROID_SDK_HOME -ne $env:ANDROID_USER_HOME) {
    throw 'Set only ANDROID_USER_HOME; conflicting Android preference paths are not supported.'
}

function Invoke-Checked([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File exited with code $LASTEXITCODE" }
}

$isWindowsHost = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$gradleWrapper = if ($isWindowsHost) { '.\gradlew.bat' } else { './gradlew' }

Write-Host 'Security audit: repository hygiene'
$trackedSensitive = @(git ls-files --error-unmatch -- keystore.properties release-upload.jks 2>$null)
if ($trackedSensitive.Count -gt 0) {
    throw 'Signing material is tracked by Git. Remove it from the index and rotate it before release.'
}

$secretPatterns = @(
    '-----BEGIN (RSA|EC|OPENSSH|PRIVATE) KEY-----',
    '(?i)(access_token|api[_-]?key|client[_-]?secret|password|storePassword|keyPassword)\s*[:=]\s*["''][^"'']{8,}["'']',
    '(?i)eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}'
)
$secretHits = @()
foreach ($pattern in $secretPatterns) {
    $secretHits += @(git grep -n -I -E $pattern -- ':!*.png' ':!*.jpg' ':!*.html' 2>$null)
    $historyMatch = @(git log --all --format= --name-only -G $pattern -- . 2>$null | Where-Object { $_ })
    if ($historyMatch.Count -gt 0) { $secretHits += 'Git history' }
}

# Scan the current worktree as well as the index. Git's file list avoids
# traversing bundled toolchains and generated directories, while still
# including untracked source/config files. Signing files are handled above.
$scanFiles = @(git ls-files --cached --others --exclude-standard 2>$null |
    Where-Object {
        $_ -notmatch '(^|[\\/])(\.git|\.gradle-local|\.android-local|build|tools[\\/]jdk17)([\\/]|$)' -and
        $_ -notin @('keystore.properties', 'release-upload.jks') -and
        [IO.Path]::GetExtension($_) -in @('.kt', '.kts', '.gradle', '.xml', '.yml', '.yaml', '.json', '.md', '.ps1', '.properties', '.toml', '.txt')
    } |
    ForEach-Object { Get-Item -LiteralPath $_ -ErrorAction SilentlyContinue } |
    Where-Object { $_.Length -lt 20MB })
foreach ($pattern in $secretPatterns) {
    $worktreeMatch = @($scanFiles | Select-String -Pattern $pattern -List -ErrorAction SilentlyContinue)
    if ($worktreeMatch.Count -gt 0) { $secretHits += 'Current worktree' }
}
if ($secretHits.Count -gt 0) {
    Write-Host 'Potential secret patterns found. Review the listed locations without copying values:'
    $secretHits | ForEach-Object { ($_ -split ':', 3)[0..1] -join ':' } | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" }
    throw 'Secret scan failed.'
}

$outputRoots = @('app\build', 'benchmark\build') | Where-Object { Test-Path -LiteralPath $_ }
foreach ($outputRoot in $outputRoots) {
    foreach ($pattern in $secretPatterns) {
        $artifactMatch = @(Get-ChildItem -LiteralPath $outputRoot -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Length -lt 20MB -and $_.Extension -notin @('.png', '.jpg', '.webp') } |
            Select-String -Pattern $pattern -List -ErrorAction SilentlyContinue)
        if ($artifactMatch.Count -gt 0) { throw "Potential secret pattern found in build output under $outputRoot." }
    }
}

Write-Host 'Security audit: Android manifest policy'
$manifestText = Get-Content -Raw 'app\src\main\AndroidManifest.xml'
$requiredManifestControls = @(
    'android:allowBackup="false"',
    'android:usesCleartextTraffic="false"',
    'android:networkSecurityConfig="@xml/network_security_config"'
)
foreach ($control in $requiredManifestControls) {
    if ($manifestText -notlike "*$control*") { throw "Missing manifest security control: $control" }
}
$networkConfig = Get-Content -Raw 'app\src\main\res\xml\network_security_config.xml'
if ($networkConfig -notlike '*cleartextTrafficPermitted="false"*') {
    throw 'Network security config does not explicitly deny cleartext traffic.'
}

Write-Host 'Security audit: Android and unit verification'
if (-not $SkipBuild) {
    Invoke-Checked $gradleWrapper @(':app:lintDebug', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleRelease')

    $mergedManifestFile = Get-ChildItem 'app\build\intermediates\merged_manifests\release' -Recurse -Filter AndroidManifest.xml -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $mergedManifestFile) { throw 'Release merged manifest was not generated.' }
    [xml]$mergedManifest = Get-Content -Raw $mergedManifestFile.FullName
    $exportedComponents = @($mergedManifest.SelectNodes("//*[local-name()='application']/*[@*[local-name()='exported']='true']"))
    $reviewedExportedNames = @('dev.haquickaccess.tv.MainActivity', 'dev.haquickaccess.tv.data.AdbCommandReceiver', 'androidx.profileinstaller.ProfileInstallReceiver')
    foreach ($component in $exportedComponents) {
        $name = $component.GetAttribute('name', 'http://schemas.android.com/apk/res/android')
        $permission = $component.GetAttribute('permission', 'http://schemas.android.com/apk/res/android')
        if ($name -notin $reviewedExportedNames) { throw "Unreviewed exported release component: $name" }
        if ($name -eq 'androidx.profileinstaller.ProfileInstallReceiver' -and $permission -ne 'android.permission.DUMP') {
            throw 'ProfileInstallReceiver is not protected by android.permission.DUMP.'
        }
        if ($name -eq 'dev.haquickaccess.tv.data.AdbCommandReceiver' -and $permission -ne 'android.permission.DUMP') {
            throw 'AdbCommandReceiver is not protected by android.permission.DUMP.'
        }
    }
}

if (-not $SkipDependencyScan) {
    Write-Host 'Security audit: resolved dependency reports'
    New-Item -ItemType Directory -Path 'build\security-audit' -Force | Out-Null
    & $gradleWrapper ':app:dependencies' '--configuration' 'debugRuntimeClasspath' 2>&1 |
        Tee-Object -FilePath 'build\security-audit\debug-runtime-dependencies.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle dependency report failed.' }
    & $gradleWrapper ':app:dependencies' '--configuration' 'releaseRuntimeClasspath' 2>&1 |
        Tee-Object -FilePath 'build\security-audit\release-runtime-dependencies.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle release dependency report failed.' }
    & $gradleWrapper ':app:buildEnvironment' 2>&1 |
        Tee-Object -FilePath 'build\security-audit\app-build-environment.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle app build-environment report failed.' }
    & $gradleWrapper 'buildEnvironment' 2>&1 |
        Tee-Object -FilePath 'build\security-audit\root-build-environment.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle root build-environment report failed.' }
    & $gradleWrapper ':app:dependencyInsight' '--dependency' 'okhttp' '--configuration' 'debugRuntimeClasspath' 2>&1 |
        Tee-Object -FilePath 'build\security-audit\okhttp-dependency-insight.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle dependency insight failed.' }
    if (Get-Command osv-scanner -ErrorAction SilentlyContinue) {
        Invoke-Checked 'osv-scanner' @('scan', 'source', '-r', '.')
    } else {
        Write-Warning 'osv-scanner is not installed; run it in CI or install it before signing a release.'
    }
}

Write-Host 'Security audit completed. Review build\security-audit and CI vulnerability results.'
