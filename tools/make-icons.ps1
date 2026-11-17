# Draws the launcher and notification icons as PNGs. Android 5-7 phones can't use the adaptive icon, so they need these.
# Same shapes as app/src/main/res/drawable/ic_launcher_foreground.xml. Windows PowerShell + System.Drawing, no other tools.
Add-Type -AssemblyName System.Drawing
$res = Join-Path $PSScriptRoot '..\app\src\main\res'

function Pt($x, $y, $s) { New-Object System.Drawing.PointF ([float]($x * $s)), ([float]($y * $s)) }

function Add-Bezier($path, $s, $p0, $c1, $c2, $p1) {
    $path.AddBezier((Pt $p0[0] $p0[1] $s), (Pt $c1[0] $c1[1] $s), (Pt $c2[0] $c2[1] $s), (Pt $p1[0] $p1[1] $s))
}

function Poly($pts, $s) { , ($pts | ForEach-Object { Pt $_[0] $_[1] $s }) }

function New-Canvas($px) {
    $bmp = New-Object System.Drawing.Bitmap $px, $px, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    return @($bmp, $g)
}

function Fill-Poly($g, $color, $pts, $s) {
    $g.FillPolygon((New-Object System.Drawing.SolidBrush $color), [System.Drawing.PointF[]](Poly $pts $s))
}

function Draw-Launcher($px, $file) {
    $bmp, $g = New-Canvas $px
    $s = $px / 108.0
    # Background: rounded square
    $r = 26 * $s
    $bg = New-Object System.Drawing.Drawing2D.GraphicsPath
    $bg.AddArc(0, 0, 2 * $r, 2 * $r, 180, 90)
    $bg.AddArc($px - 2 * $r, 0, 2 * $r, 2 * $r, 270, 90)
    $bg.AddArc($px - 2 * $r, $px - 2 * $r, 2 * $r, 2 * $r, 0, 90)
    $bg.AddArc(0, $px - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $bg.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0x2A, 0x1A, 0x10))), $bg)

    $body = New-Object System.Drawing.Drawing2D.GraphicsPath
    Add-Bezier $body $s @(54, 32) @(36, 32) @(26, 44) @(26, 58)
    Add-Bezier $body $s @(26, 58) @(26, 72) @(36, 82) @(54, 82)
    Add-Bezier $body $s @(54, 82) @(72, 82) @(82, 72) @(82, 58)
    Add-Bezier $body $s @(82, 58) @(82, 44) @(72, 32) @(54, 32)
    $body.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0xF5, 0x7C, 0x00))), $body)

    $rib = New-Object System.Drawing.Drawing2D.GraphicsPath
    Add-Bezier $rib $s @(54, 32) @(46, 36) @(42, 46) @(42, 58)
    Add-Bezier $rib $s @(42, 58) @(42, 70) @(46, 78) @(54, 82)
    Add-Bezier $rib $s @(54, 82) @(62, 78) @(66, 70) @(66, 58)
    Add-Bezier $rib $s @(66, 58) @(66, 46) @(62, 36) @(54, 32)
    $rib.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0xFF, 0xA7, 0x26))), $rib)

    Fill-Poly $g ([System.Drawing.Color]::FromArgb(255, 0x5C, 0x8F, 0x2E)) @(@(51, 21), @(58, 21), @(59, 34), @(49, 34)) $s
    $dark = [System.Drawing.Color]::FromArgb(255, 0x2A, 0x1A, 0x10)
    Fill-Poly $g $dark @(@(38, 52), @(48, 52), @(43, 61)) $s
    Fill-Poly $g $dark @(@(60, 52), @(70, 52), @(65, 61)) $s
    Fill-Poly $g $dark @(@(42, 68), @(66, 68), @(62, 75), @(58, 72), @(54, 75), @(50, 72), @(46, 75)) $s

    New-Item -ItemType Directory -Force (Split-Path $file) | Out-Null
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

function Draw-Stat($px, $file) {
    # White silhouette with transparent eyes and mouth (even-odd), like the original vector icon.
    $bmp, $g = New-Canvas $px
    $s = $px / 24.0
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath ([System.Drawing.Drawing2D.FillMode]::Alternate)
    $body = New-Object System.Drawing.Drawing2D.GraphicsPath
    Add-Bezier $body $s @(12, 7) @(7.5, 7) @(5, 10) @(5, 13.5)
    Add-Bezier $body $s @(5, 13.5) @(5, 17) @(7.5, 19.5) @(12, 19.5)
    Add-Bezier $body $s @(12, 19.5) @(16.5, 19.5) @(19, 17) @(19, 13.5)
    Add-Bezier $body $s @(19, 13.5) @(19, 10) @(16.5, 7) @(12, 7)
    $body.CloseFigure()
    $path.AddPath($body, $false)
    $path.AddPolygon([System.Drawing.PointF[]](Poly @(@(8.5, 12), @(10.5, 12), @(9.5, 14)) $s))
    $path.AddPolygon([System.Drawing.PointF[]](Poly @(@(13.5, 12), @(15.5, 12), @(14.5, 14)) $s))
    $path.AddPolygon([System.Drawing.PointF[]](Poly @(@(9, 15.5), @(15, 15.5), @(14, 17), @(12, 16.3), @(10, 17)) $s))
    $g.FillPath([System.Drawing.Brushes]::White, $path)
    Fill-Poly $g ([System.Drawing.Color]::White) @(@(11.3, 4), @(12.8, 4), @(13, 7), @(11, 7)) $s
    New-Item -ItemType Directory -Force (Split-Path $file) | Out-Null
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

$launcher = [ordered]@{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
$stat = [ordered]@{ 'mdpi' = 24; 'hdpi' = 36; 'xhdpi' = 48; 'xxhdpi' = 72; 'xxxhdpi' = 96 }
foreach ($d in $launcher.Keys) { Draw-Launcher $launcher[$d] (Join-Path $res "mipmap-$d\ic_launcher.png") }
foreach ($d in $stat.Keys) { Draw-Stat $stat[$d] (Join-Path $res "drawable-$d\ic_stat_server.png") }
Write-Host "Icons written to $res"
