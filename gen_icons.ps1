# 生成圆角 Launcher 图标（System.Drawing，无第三方依赖）
# 输出:
#   - mipmap-*/ic_launcher.png        (legacy 圆角图标, 48/72/96/144/192)
#   - drawable-xxxhdpi/ic_launcher_fg.png (adaptive icon 前景, 透明背景手柄, 432px)
Add-Type -AssemblyName System.Drawing

$resDir = "L:\steamlike\app\src\main\res"

function New-RoundRectPath {
    param([float]$x, [float]$y, [float]$w, [float]$h, [float]$r)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2.0
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

# 在给定 Graphics 上绘制游戏手柄（以画布尺寸 s 为基准归一化），中心在 (cx, cy)
function Draw-Gamepad {
    param([System.Drawing.Graphics]$g, [float]$s, [float]$cx, [float]$cy)
    $solid = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 236, 239, 246))
    $dark = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 46, 48, 60))
    $accent = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 64, 128, 255))

    # 机身：圆角矩形
    $bodyW = $s * 0.58
    $bodyH = $s * 0.34
    $bodyX = $cx - $bodyW / 2
    $bodyY = $cy - $bodyH / 2
    $body = New-RoundRectPath -x $bodyX -y $bodyY -w $bodyW -h $bodyH -r ($s * 0.055)
    $g.FillPath($solid, $body)

    # 左右摇杆（深色圆环，中心内缩）
    $dx1 = -0.13 * $s
    $dx2 = 0.13 * $s
    foreach ($dx in @($dx1, $dx2)) {
        $stickX = $cx + $dx
        $stickY = $cy
        $rOuter = $s * 0.055
        $rInner = $s * 0.028
        $g.FillEllipse($dark, $stickX - $rOuter, $stickY - $rOuter, $rOuter * 2, $rOuter * 2)
        $g.FillEllipse($solid, $stickX - $rInner, $stickY - $rInner, $rInner * 2, $rInner * 2)
    }

    # 中间十字键（D-pad）
    $barW = $s * 0.035
    $barH = $s * 0.13
    $barR = $s * 0.012
    $crossX = $cx
    $crossY = $cy
    $hb = New-RoundRectPath -x ($crossX - $barH / 2) -y ($crossY - $barW / 2) -w $barH -h $barW -r $barR
    $vb = New-RoundRectPath -x ($crossX - $barW / 2) -y ($crossY - $barH / 2) -w $barW -h $barH -r $barR
    $g.FillPath($dark, $hb)
    $g.FillPath($dark, $vb)

    # 顶部 LB/RB 小按钮
    $dxb1 = -0.205 * $s
    $dxb2 = 0.205 * $s
    foreach ($dx in @($dxb1, $dxb2)) {
        $btnW = $s * 0.07
        $btnH = $s * 0.045
        $btnX = $cx + $dx - $btnW / 2
        $btnY = $bodyY - $btnH * 0.6
        $btn = New-RoundRectPath -x $btnX -y $btnY -w $btnW -h $btnH -r ($btnH * 0.4)
        $g.FillPath($accent, $btn)
    }

    $solid.Dispose(); $dark.Dispose(); $accent.Dispose()
}

# 1) Legacy 圆角图标（深色圆角背景 + 手柄）
function New-LegacyIcon {
    param([int]$size, [string]$out)
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)
    $s = [float]$size
    # 圆角背景（深色渐变）
    $r = $s * 0.20
    $bg = New-RoundRectPath -x 0 -y 0 -w $s -h $s -r $r
    $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.RectangleF(0, 0, $s, $s)),
        [System.Drawing.Color]::FromArgb(255, 46, 49, 64),
        [System.Drawing.Color]::FromArgb(255, 24, 26, 36),
        45)
    $g.FillPath($grad, $bg)
    Draw-Gamepad -g $g -s $s -cx ($s * 0.5) -cy ($s * 0.5)
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

# 2) Adaptive icon 前景（透明背景 + 手柄）
function New-ForegroundIcon {
    param([int]$size, [string]$out)
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)
    $s = [float]$size
    Draw-Gamepad -g $g -s $s -cx ($s * 0.5) -cy ($s * 0.5)
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

# Legacy 各密度
$densities = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}
foreach ($entry in $densities.GetEnumerator()) {
    $dir = Join-Path $resDir $entry.Key
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    New-LegacyIcon -size ([int]$entry.Value) -out (Join-Path $dir "ic_launcher.png")
    Write-Host "generated $($entry.Key)/ic_launcher.png ($($entry.Value)px)"
}

# Adaptive 前景
$fgDir = Join-Path $resDir "drawable-xxxhdpi"
New-Item -ItemType Directory -Force -Path $fgDir | Out-Null
New-ForegroundIcon -size 432 -out (Join-Path $fgDir "ic_launcher_fg.png")
Write-Host "generated drawable-xxxhdpi/ic_launcher_fg.png (432px)"

Write-Host "DONE"
