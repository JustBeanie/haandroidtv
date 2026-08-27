param(
    [Parameter(Mandatory = $true)]
    [string]$FeatureSource,
    [string]$OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'play-store-assets')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$sourceDirectory = Join-Path $OutputDirectory 'source'
New-Item -ItemType Directory -Path $sourceDirectory -Force | Out-Null

$featureSourceCopy = Join-Path $sourceDirectory 'feature-graphic-generated.png'
$featureOutput = Join-Path $OutputDirectory 'feature-graphic-1280x720.png'
$iconOutput = Join-Path $OutputDirectory 'app-icon-512x512.png'
Copy-Item -LiteralPath $FeatureSource -Destination $featureSourceCopy -Force

function New-Canvas([int]$Width, [int]$Height) {
    New-Object System.Drawing.Bitmap(
        $Width,
        $Height,
        [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
    )
}

# Render a deterministic Play listing icon from the app's existing Android
# vector mark. Keeping this code-native prevents the store identity from
# drifting away from the installed launcher icon.
$icon = New-Canvas 512 512
$iconGraphics = [System.Drawing.Graphics]::FromImage($icon)
try {
    $iconGraphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $iconGraphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#0B1323'))

    $scale = 512.0 / 108.0
    $housePoints = @(
        [System.Drawing.PointF]::new(54 * $scale, 13 * $scale),
        [System.Drawing.PointF]::new(96 * $scale, 49 * $scale),
        [System.Drawing.PointF]::new(96 * $scale, 94 * $scale),
        [System.Drawing.PointF]::new(68 * $scale, 94 * $scale),
        [System.Drawing.PointF]::new(68 * $scale, 64 * $scale),
        [System.Drawing.PointF]::new(40 * $scale, 64 * $scale),
        [System.Drawing.PointF]::new(40 * $scale, 94 * $scale),
        [System.Drawing.PointF]::new(12 * $scale, 94 * $scale),
        [System.Drawing.PointF]::new(12 * $scale, 49 * $scale)
    )
    $cyanBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#59D5FF'))
    $whiteBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#F6F8FF'))
    try {
        $iconGraphics.FillPolygon($cyanBrush, $housePoints)
        $iconGraphics.FillEllipse($whiteBrush, 41 * $scale, 33 * $scale, 26 * $scale, 26 * $scale)
        $iconGraphics.FillRectangle($whiteBrush, 48 * $scale, 54 * $scale, 12 * $scale, 30 * $scale)
    } finally {
        $cyanBrush.Dispose()
        $whiteBrush.Dispose()
    }
    $icon.Save($iconOutput, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $iconGraphics.Dispose()
    $icon.Dispose()
}

# Center-crop the generated artwork to exactly 16:9, then use high-quality
# resampling for the Play Console's required 1280 x 720 output.
$source = [System.Drawing.Image]::FromFile($featureSourceCopy)
$feature = New-Canvas 1280 720
$featureGraphics = [System.Drawing.Graphics]::FromImage($feature)
try {
    $featureGraphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $featureGraphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $featureGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $featureGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $featureGraphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality

    $targetAspect = 16.0 / 9.0
    $sourceAspect = $source.Width / [double]$source.Height
    if ($sourceAspect -gt $targetAspect) {
        $cropHeight = [double]$source.Height
        $cropWidth = $cropHeight * $targetAspect
        $cropX = ($source.Width - $cropWidth) / 2.0
        $cropY = 0.0
    } else {
        $cropWidth = [double]$source.Width
        $cropHeight = $cropWidth / $targetAspect
        $cropX = 0.0
        $cropY = ($source.Height - $cropHeight) / 2.0
    }

    $destinationRectangle = [System.Drawing.RectangleF]::new(0, 0, 1280, 720)
    $sourceRectangle = [System.Drawing.RectangleF]::new($cropX, $cropY, $cropWidth, $cropHeight)
    $featureGraphics.DrawImage(
        $source,
        $destinationRectangle,
        $sourceRectangle,
        [System.Drawing.GraphicsUnit]::Pixel
    )
    $feature.Save($featureOutput, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $featureGraphics.Dispose()
    $feature.Dispose()
    $source.Dispose()
}

Write-Output "Created $iconOutput"
Write-Output "Created $featureOutput"
