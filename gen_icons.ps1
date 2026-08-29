# 生成圆角 Launcher 图标（System.Drawing，无第三方依赖）
# 输出:
#   - mipmap-*/ic_launcher.png        (legacy 圆角图标, 48/72/96/144/192)
#   - drawable-xxxhdpi/ic_launcher_fg.png (adaptive icon 前景, 透明背景手柄, 432px)
Add-Type -AssemblyName System.Drawing # 语法：Add-Type 加载 .NET 程序集；System.Drawing 提供绘图 API

$resDir = "L:\steamlike\app\src\main\res" # 语法：$变量 定义 Android res 资源目录的绝对路径

function New-RoundRectPath { # 语法：function 定义函数 New-RoundRectPath，创建圆角矩形路径
    param([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) # 语法：param 声明参数；[float] 类型约束为单精度浮点；$x/$y 左上角、$w/$h 宽高、$r 圆角半径
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath # 语法：New-Object 创建对象；GraphicsPath 图形路径对象，可累积圆弧/闭合图形
    $d = $r * 2.0 # 语法：$变量 计算直径 d = 半径 r 的 2 倍
    $path.AddArc($x, $y, $d, $d, 180, 90) # 语法：.AddArc() 添加椭圆弧线（左上角圆角，180°~270°）
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90) # 添加右上角圆弧（270°~360°）
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90) # 添加右下角圆弧（0°~90°）
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90) # 添加左下角圆弧（90°~180°）
    $path.CloseFigure() # 语法：.CloseFigure() 闭合图形，连接终点与起点
    return $path # 语法：return 返回创建的圆角路径对象
}

# 在给定 Graphics 上绘制游戏手柄（以画布尺寸 s 为基准归一化），中心在 (cx, cy)
function Draw-Gamepad { # 语法：function 定义函数 Draw-Gamepad，负责绘制手柄图案
    param([System.Drawing.Graphics]$g, [float]$s, [float]$cx, [float]$cy) # 语法：param 声明参数；$g 绘图对象、$s 画布边长、$cx/$cy 中心坐标
    $solid = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 236, 239, 246)) # 语法：New-Object 创建画刷；SolidBrush 纯色画刷；Color::FromArgb(255,R,G,B) 由 ARGB 值构造颜色（浅灰白）
    $dark = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 46, 48, 60)) # 创建深色画刷（深蓝灰，用于摇杆等）
    $accent = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 64, 128, 255)) # 创建强调色画刷（蓝色，用于肩键）

    # 机身：圆角矩形
    $bodyW = $s * 0.58 # 语法：$变量 计算机身宽度 = 画布尺寸 * 0.58
    $bodyH = $s * 0.34 # 计算机身高度 = 画布尺寸 * 0.34
    $bodyX = $cx - $bodyW / 2 # 计算机身左上角 X = 中心 X - 宽度一半（水平居中）
    $bodyY = $cy - $bodyH / 2 # 计算机身左上角 Y = 中心 Y - 高度一半（垂直居中）
    $body = New-RoundRectPath -x $bodyX -y $bodyY -w $bodyW -h $bodyH -r ($s * 0.055) # 语法：调用函数并使用具名参数；创建机身圆角矩形路径
    $g.FillPath($solid, $body) # 语法：.FillPath() 用画刷填充路径；用浅色画刷填充机身

    # 左右摇杆（深色圆环，中心内缩）
    $dx1 = -0.13 * $s # 语法：$变量 左摇杆相对中心 X 偏移
    $dx2 = 0.13 * $s # 右摇杆相对中心 X 偏移
    foreach ($dx in @($dx1, $dx2)) { # 语法：foreach 循环遍历数组 @() 中的两个偏移量，分别画左右摇杆
        $stickX = $cx + $dx # 语法：$变量 当前摇杆中心 X 坐标
        $stickY = $cy # 当前摇杆中心 Y 坐标（与中心一致）
        $rOuter = $s * 0.055 # 外环半径
        $rInner = $s * 0.028 # 内环半径
        $g.FillEllipse($dark, $stickX - $rOuter, $stickY - $rOuter, $rOuter * 2, $rOuter * 2) # 语法：.FillEllipse() 填充椭圆；先画深色外环
        $g.FillEllipse($solid, $stickX - $rInner, $stickY - $rInner, $rInner * 2, $rInner * 2) # 再画浅色内环（形成圆环效果）
    }

    # 中间十字键（D-pad）
    $barW = $s * 0.035 # 语法：$变量 十字键横条宽度
    $barH = $s * 0.13 # 十字键横条长度（高度方向）
    $barR = $s * 0.012 # 十字键圆角半径
    $crossX = $cx # 语法：$变量 十字键中心 X（画布中心）
    $crossY = $cy # 十字键中心 Y（画布中心）
    $hb = New-RoundRectPath -x ($crossX - $barH / 2) -y ($crossY - $barW / 2) -w $barH -h $barW -r $barR # 语法：调用函数；创建水平横条路径（宽=barH，高=barW，即横向放置的长条）
    $vb = New-RoundRectPath -x ($crossX - $barW / 2) -y ($crossY - $barH / 2) -w $barW -h $barH -r $barR # 创建垂直竖条路径（宽=barW，高=barH，即竖向放置的长条）
    $g.FillPath($dark, $hb) # 语法：.FillPath() 用深色画刷填充水平横条
    $g.FillPath($dark, $vb) # 用深色画刷填充垂直竖条（与横条组成十字）

    # 顶部 LB/RB 小按钮
    $dxb1 = -0.205 * $s # 语法：$变量 左肩键相对中心 X 偏移
    $dxb2 = 0.205 * $s # 右肩键相对中心 X 偏移
    foreach ($dx in @($dxb1, $dxb2)) { # 语法：foreach 循环遍历两个偏移量，分别画左右肩键
        $btnW = $s * 0.07 # 语法：$变量 肩键宽度
        $btnH = $s * 0.045 # 肩键高度
        $btnX = $cx + $dx - $btnW / 2 # 肩键左上角 X（以中心偏移居中）
        $btnY = $bodyY - $btnH * 0.6 # 肩键左上角 Y（在机身顶部上方一点）
        $btn = New-RoundRectPath -x $btnX -y $btnY -w $btnW -h $btnH -r ($btnH * 0.4) # 语法：调用函数；创建肩键圆角矩形路径
        $g.FillPath($accent, $btn) # 语法：.FillPath() 用强调色画刷填充肩键
    }

    $solid.Dispose(); $dark.Dispose(); $accent.Dispose() # 语法：.Dispose() 释放画刷占用的 GDI 资源；分号 ; 分隔同行的多条语句
}

# 1) Legacy 圆角图标（深色圆角背景 + 手柄）
function New-LegacyIcon { # 语法：function 定义函数 New-LegacyIcon，生成传统圆角图标
    param([int]$size, [string]$out) # 语法：param 声明参数；[int] 整数类型尺寸、[string] 字符串类型输出路径
    $bmp = New-Object System.Drawing.Bitmap($size, $size) # 语法：New-Object 创建位图对象；Bitmap(宽,高) 尺寸为 size×size 像素
    $g = [System.Drawing.Graphics]::FromImage($bmp) # 语法：[类型]::静态方法 调用静态方法；Graphics::FromImage 从位图获取绘图对象
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias # 语法：属性赋值；SmoothingMode=AntiAlias 开启抗锯齿使边缘平滑
    $g.Clear([System.Drawing.Color]::Transparent) # 语法：.Clear() 清空画布为指定颜色；Transparent 透明背景
    $s = [float]$size # 语法：[float] 类型转换；把整数尺寸转为浮点数便于乘法计算
    # 圆角背景（深色渐变）
    $r = $s * 0.20 # 语法：$变量 背景圆角半径 = 尺寸 * 0.20
    $bg = New-RoundRectPath -x 0 -y 0 -w $s -h $s -r $r # 语法：调用函数；创建铺满整个画布的圆角矩形路径
    $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush( # 语法：New-Object 创建对象；LinearGradientBrush 线性渐变画刷（参数跨多行）
        (New-Object System.Drawing.RectangleF(0, 0, $s, $s)), # 语法：RectangleF 浮点矩形对象，定义渐变的作用区域
        [System.Drawing.Color]::FromArgb(255, 46, 49, 64), # 渐变起始颜色（深蓝灰）
        [System.Drawing.Color]::FromArgb(255, 24, 26, 36), # 渐变结束颜色（更深的蓝黑）
        45) # 渐变角度 45°（左下到右上方向）
    $g.FillPath($grad, $bg) # 语法：.FillPath() 用渐变画刷填充圆角背景
    Draw-Gamepad -g $g -s $s -cx ($s * 0.5) -cy ($s * 0.5) # 语法：调用函数并传入具名参数；在画布中心绘制手柄
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png) # 语法：.Save(路径, 格式) 保存位图为 PNG 文件
    $g.Dispose(); $bmp.Dispose() # 语法：.Dispose() 释放绘图对象与位图资源；分号分隔多条语句
}

# 2) Adaptive icon 前景（透明背景 + 手柄）
function New-ForegroundIcon { # 语法：function 定义函数 New-ForegroundIcon，生成自适应图标前景
    param([int]$size, [string]$out) # 语法：param 声明参数；尺寸与输出路径
    $bmp = New-Object System.Drawing.Bitmap($size, $size) # 语法：New-Object 创建 size×size 位图
    $g = [System.Drawing.Graphics]::FromImage($bmp) # 语法：[类型]::静态方法 获取绘图对象
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias # 语法：属性赋值；开启抗锯齿
    $g.Clear([System.Drawing.Color]::Transparent) # 语法：.Clear() 清空为透明背景
    $s = [float]$size # 语法：[float] 类型转换；转浮点
    Draw-Gamepad -g $g -s $s -cx ($s * 0.5) -cy ($s * 0.5) # 语法：调用函数；在画布中心绘制手柄（前景只有手柄，无背景）
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png) # 语法：.Save() 保存为 PNG
    $g.Dispose(); $bmp.Dispose() # 语法：.Dispose() 释放资源
}

# Legacy 各密度
$densities = @{ # 语法：@{} 创建哈希表（键值对集合）；映射各 mipmap 目录名到图标尺寸
    "mipmap-mdpi" = 48 # 语法：键 = 值；mdpi 密度图标 48px
    "mipmap-hdpi" = 72 # hdpi 密度图标 72px
    "mipmap-xhdpi" = 96 # xhdpi 密度图标 96px
    "mipmap-xxhdpi" = 144 # xxhdpi 密度图标 144px
    "mipmap-xxxhdpi" = 192 # xxxhdpi 密度图标 192px
}
foreach ($entry in $densities.GetEnumerator()) { # 语法：foreach 遍历哈希表；.GetEnumerator() 返回键值对枚举器，逐个密度处理
    $dir = Join-Path $resDir $entry.Key # 语法：Join-Path 拼接路径；$entry.Key 为 mipmap 目录名，得到完整输出目录
    New-Item -ItemType Directory -Force -Path $dir | Out-Null # 语法：New-Item 创建目录；-ItemType Directory 指定为目录；-Force 已存在也不报错；管道 | Out-Null 丢弃输出
    New-LegacyIcon -size ([int]$entry.Value) -out (Join-Path $dir "ic_launcher.png") # 语法：调用函数；[int] 转换值；生成对应尺寸的 ic_launcher.png
    Write-Host "generated $($entry.Key)/ic_launcher.png ($($entry.Value)px)" # 语法：Write-Host 输出信息；$() 在字符串内执行子表达式取键/值
}

# Adaptive 前景
$fgDir = Join-Path $resDir "drawable-xxxhdpi" # 语法：Join-Path 拼接路径；得到自适应图标前景输出目录
New-Item -ItemType Directory -Force -Path $fgDir | Out-Null # 语法：New-Item 创建目录；-Force 幂等；| Out-Null 丢弃输出
New-ForegroundIcon -size 432 -out (Join-Path $fgDir "ic_launcher_fg.png") # 语法：调用函数；生成 432px 自适应图标前景
Write-Host "generated drawable-xxxhdpi/ic_launcher_fg.png (432px)" # 语法：Write-Host 输出生成成功信息

Write-Host "DONE" # 语法：Write-Host 输出全部完成标记
