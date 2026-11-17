[CmdletBinding()]
param([string]$Artifact = 'app/build/outputs/apk/release/app-release.apk')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-BigEndianInt([byte[]]$Bytes, [int]$Offset) {
    $part = [byte[]]$Bytes[$Offset..($Offset + 3)]
    [Array]::Reverse($part)
    return [BitConverter]::ToUInt32($part, 0)
}
$assets = @(
    @('app-icon-512x512.png', 512, 512),
    @('feature-graphic-1024x500.png', 1024, 500),
    @('tv-banner-1280x720.png', 1280, 720)
)
foreach ($asset in $assets) {
    $bytes = [IO.File]::ReadAllBytes((Join-Path $projectRoot ('play-store-assets/' + $asset[0])))
    if ([BitConverter]::ToString($bytes[0..7]) -ne '89-50-4E-47-0D-0A-1A-0A') { throw "Not a PNG: $($asset[0])" }
    if ((Read-BigEndianInt $bytes 16) -ne $asset[1] -or (Read-BigEndianInt $bytes 20) -ne $asset[2]) { throw "Wrong dimensions: $($asset[0])" }
}

$artifactPath = if ([IO.Path]::IsPathRooted($Artifact)) { $Artifact } else { Join-Path $projectRoot $Artifact }
$archive = [IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $libraries = @($archive.Entries | Where-Object { $_.FullName -match '(^|/)lib/[^/]+/[^/]+[.]so$' })
    foreach ($abi in @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')) {
        if (-not ($libraries | Where-Object { $_.FullName -like "*/$abi/*.so" })) { throw "Missing native ABI: $abi" }
    }
    foreach ($entry in $libraries) {
        $stream = $entry.Open()
        $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
        if ([BitConverter]::ToString($bytes[0..3]) -ne '7F-45-4C-46' -or $bytes[5] -ne 1) { throw "Unsupported ELF: $($entry.FullName)" }
        if ($bytes[4] -ne 2) { continue } # Android 16 KB devices use 64-bit ABIs.
        $offset = [BitConverter]::ToUInt64($bytes, 32)
        $size = [BitConverter]::ToUInt16($bytes, 54)
        $count = [BitConverter]::ToUInt16($bytes, 56)
        for ($index = 0; $index -lt $count; $index++) {
            $header = [int]($offset + $index * $size)
            if ([BitConverter]::ToUInt32($bytes, $header) -eq 1) {
                $alignment = [BitConverter]::ToUInt64($bytes, $header + 48)
                if ($alignment -lt 16384) { throw "ELF load segment is not 16 KB aligned: $($entry.FullName)" }
            }
        }
    }
    Write-Host "Verified listing dimensions, four native ABIs, and 16 KB ELF alignment ($($libraries.Count) libraries)."
} finally { $archive.Dispose() }
