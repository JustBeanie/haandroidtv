$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$fontFamily = [System.Drawing.FontFamily]::new('Segoe UI')
$format = [System.Drawing.StringFormat]::GenericTypographic
$path = [System.Drawing.Drawing2D.GraphicsPath]::new()
try {
    $path.AddString('HA Quick', $fontFamily, 1, 26, [System.Drawing.PointF]::new(100, 54), $format)
    $path.AddString('Access', $fontFamily, 1, 26, [System.Drawing.PointF]::new(100, 87), $format)
    $points = $path.PathPoints
    $types = $path.PathTypes
    $commands = [System.Collections.Generic.List[string]]::new()
    function Coordinate($point) { $point.X.ToString('0.##', [Globalization.CultureInfo]::InvariantCulture) + ',' + $point.Y.ToString('0.##', [Globalization.CultureInfo]::InvariantCulture) }
    for ($index = 0; $index -lt $points.Length; $index++) {
        switch ($types[$index] -band 7) {
            0 { $commands.Add('M' + (Coordinate $points[$index])) }
            1 { $commands.Add('L' + (Coordinate $points[$index])) }
            3 { $commands.Add('C' + (Coordinate $points[$index]) + ' ' + (Coordinate $points[$index + 1]) + ' ' + (Coordinate $points[$index + 2])); $index += 2 }
        }
        if ($types[$index] -band 128) { $commands.Add('Z') }
    }
    $textPath = $commands -join ' '
    $vector = @"
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="160dp" android:height="90dp" android:viewportWidth="320" android:viewportHeight="180">
    <path android:fillColor="#FF080D19" android:pathData="M0,0h320v180H0z" />
    <path android:fillColor="#FF59D5FF" android:pathData="M55,54L85,80v43H66v-26H44v26H25V80z" />
    <path android:fillColor="#FFF6F8FF" android:pathData="$textPath" />
</vector>
"@
    $vector | Set-Content -LiteralPath (Join-Path $root 'app/src/main/res/drawable/tv_banner.xml') -Encoding utf8
    $bitmap = [System.Drawing.Bitmap]::new(1280, 720, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $cyan = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#59D5FF'))
    $white = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#F6F8FF'))
    try {
        $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#080D19'))
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.ScaleTransform(4, 4)
        $house = @([Drawing.PointF]::new(55,54),[Drawing.PointF]::new(85,80),[Drawing.PointF]::new(85,123),[Drawing.PointF]::new(66,123),[Drawing.PointF]::new(66,97),[Drawing.PointF]::new(44,97),[Drawing.PointF]::new(44,123),[Drawing.PointF]::new(25,123),[Drawing.PointF]::new(25,80))
        $graphics.FillPolygon($cyan, $house)
        $graphics.FillPath($white, $path)
        $bitmap.Save((Join-Path $root 'play-store-assets/tv-banner-1280x720.png'), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose(); $cyan.Dispose(); $white.Dispose() }
} finally { $path.Dispose(); $fontFamily.Dispose() }
