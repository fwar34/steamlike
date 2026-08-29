<#
.SYNOPSIS
InputBridge Client Control Script for PowerShell

.DESCRIPTION
Usage:
  .\control.ps1 start    - Start inputbridge_client.exe
  .\control.ps1 stop     - Stop inputbridge_client.exe
  .\control.ps1 status   - Show running status
  .\control.ps1 restart  - Restart inputbridge_client.exe
  .\control.ps1 help     - Show this help

.NOTE: Run build.bat first to compile the exe.
#>

param( # 语法：param 声明脚本的参数块
    [Parameter(Mandatory = $false)] # 语法：参数特性定义；Mandatory=$false 表示该参数可选，不强制
    [string]$command # 语法：[string] 类型约束；$command 为存放命令名（start/stop 等）的变量
) # 结束 param 参数块

$EXE_NAME = "inputbridge_client.exe" # 语法：$变量 定义客户端可执行文件名
# 兼容旧版PowerShell获取脚本目录
$scriptPath = $MyInvocation.MyCommand.Definition # 语法：$变量；$MyInvocation.MyCommand.Definition 获取当前脚本的完整路径
$scriptDir = Split-Path -Path $scriptPath -Parent # 语法：Split-Path 拆分路径；-Parent 返回脚本所在目录
$EXE_PATH = Join-Path -Path $scriptDir -ChildPath $EXE_NAME # 语法：Join-Path 拼接目录与文件名得到 exe 完整路径
$GAME_EXE = "__GAME_EXE__" # 导出时会被替换为实际游戏路径 # 语法：$变量 定义游戏路径，导出时由 App 替换占位符

function Show-Help { # 语法：function 定义函数 Show-Help，显示帮助信息
    Write-Host "========================================" # 语法：Write-Host 输出文本到控制台
    Write-Host "  InputBridge Client Control Script" # 输出帮助标题
    Write-Host "========================================" # 输出标题分隔线
    Write-Host "" # 输出空行
    Write-Host "Usage: .\control.ps1 [command]" # 输出用法说明
    Write-Host "" # 输出空行
    Write-Host "Commands:" # 输出命令列表标题
    Write-Host "  start    Start inputbridge_client.exe" # 输出 start 命令说明
    Write-Host "  stop     Stop inputbridge_client.exe" # 输出 stop 命令说明
    Write-Host "  status   Show running status and port info" # 输出 status 命令说明
    Write-Host "  restart  Stop then start" # 输出 restart 命令说明
    Write-Host "  help     Show this help message" # 输出 help 命令说明
    Write-Host "" # 输出空行
    Write-Host "Default server: 127.0.0.1:27015" # 输出默认服务器信息
    Write-Host "" # 输出空行
} # 结束函数 Show-Help

function Start-Game { # 语法：function 定义函数 Start-Game，负责启动游戏
    if ([string]::IsNullOrEmpty($GAME_EXE)) { # 语法：if 判断；[string]::IsNullOrEmpty 静态方法判断字符串为空
        Write-Host "[INFO] No game EXE configured, skip game launch." # 提示未配置游戏路径，跳过启动
        return $true # 语法：return 返回布尔值 $true 表示成功
    }
    if (-not (Test-Path -Path $GAME_EXE)) { # 语法：if 判断；-not 逻辑非；Test-Path 测试路径是否存在
        Write-Host "[WARN] Game EXE not found: $GAME_EXE" # 警告游戏文件不存在
        Write-Host "[INFO] Please set the correct path in SteamLike App (Windows client page)." # 提示在 App 中设置路径
        return $false # 语法：return 返回布尔值 $false 表示失败
    }
    $GAME_PROCESS = (Get-Item -Path $GAME_EXE).Name # 语法：Get-Item 获取文件对象；.Name 取文件名（用于进程匹配）
    Write-Host "[INFO] Starting game: $GAME_EXE" # 输出正在启动游戏及路径
    Start-Process -FilePath $GAME_EXE -NoNewWindow # 语法：Start-Process 启动新进程；-NoNewWindow 在现有窗口运行
    Start-Sleep -Seconds 3 # 语法：Start-Sleep 暂停指定的秒数，等待游戏启动
    $gameRunning = Get-Process -Name $GAME_PROCESS -ErrorAction SilentlyContinue # 语法：Get-Process 查询进程；-Name 按名称；-ErrorAction SilentlyContinue 出错时静默
    if ($gameRunning) { # 语法：if 判断是否找到游戏进程
        Write-Host "[OK] Game started: $GAME_PROCESS" # 输出游戏启动成功
        return $true # 语法：return 返回 $true 表示成功
    } else { # 语法：else 未找到进程分支
        Write-Host "[WARN] Game process $GAME_PROCESS not detected after launch." # 警告未检测到游戏进程
        Write-Host "[INFO] Input bridge will NOT be started." # 提示将不启动输入桥接
        return $false # 语法：return 返回 $false 表示失败
    }
} # 结束函数 Start-Game

function Start-InputBridge { # 语法：function 定义函数 Start-InputBridge，负责启动客户端
    # Check if already running
    $existingProcess = Get-Process -Name $EXE_NAME -ErrorAction SilentlyContinue # 语法：Get-Process 查询客户端是否已在运行；-ErrorAction SilentlyContinue 静默
    if ($existingProcess) { # 语法：if 判断客户端已在运行
        Write-Host "[INFO] $EXE_NAME is already running." # 提示客户端已在运行
        return # 语法：return 直接返回，不做后续操作
    }
    # Check if exe exists
    if (-not (Test-Path -Path $EXE_PATH)) { # 语法：if 判断；-not 逻辑非；Test-Path 测试 exe 是否存在
        Write-Host "[ERROR] $EXE_PATH not found." # 输出错误：exe 文件不存在
        Write-Host "[INFO] Please run build.bat first to compile the exe." # 提示先运行 build.bat 编译
        return # 语法：return 直接返回
    }
    Write-Host "[INFO] Starting $EXE_NAME ..." # 输出开始启动客户端
    Write-Host "[INFO] Server: 127.0.0.1:27015" # 输出服务器地址与端口
    Write-Host "[INFO] Opening console window for output ..." # 提示打开控制台窗口显示输出
    Start-Process -FilePath $EXE_PATH -ArgumentList "127.0.0.1 27015" -NoNewWindow # 语法：Start-Process 启动客户端；-ArgumentList 传入命令行参数
    Start-Sleep -Seconds 2 # 语法：Start-Sleep 暂停 2 秒，等待客户端启动
    $bridgeRunning = Get-Process -Name $EXE_NAME -ErrorAction SilentlyContinue # 语法：Get-Process 检查客户端是否已启动成功
    if ($bridgeRunning) { # 语法：if 判断客户端已启动
        Write-Host "[OK] $EXE_NAME started successfully." # 输出启动成功
    } else { # 语法：else 未启动成功分支
        Write-Host "[ERROR] Failed to start $EXE_NAME. It may have exited immediately." # 输出启动失败提示
        Write-Host "[INFO] Possible cause: another instance holds the single-instance lock." # 提示可能被单实例锁占用
    }
} # 结束函数 Start-InputBridge

function Stop-InputBridge { # 语法：function 定义函数 Stop-InputBridge，负责停止客户端
    $existingProcess = Get-Process -Name $EXE_NAME -ErrorAction SilentlyContinue # 语法：Get-Process 查询客户端进程是否存在
    if (-not $existingProcess) { # 语法：if 判断；-not 逻辑非；客户端未在运行
        Write-Host "[INFO] $EXE_NAME is not running." # 提示客户端未在运行
        return # 语法：return 直接返回
    }
    Write-Host "[INFO] Stopping $EXE_NAME ..." # 输出开始停止客户端
    try { # 语法：try 开始异常捕获块
        Stop-Process -Name $EXE_NAME -Force -ErrorAction Stop # 语法：Stop-Process 结束进程；-Force 强制；-ErrorAction Stop 出错时抛异常
        Write-Host "[OK] $EXE_NAME stopped." # 输出停止成功
    } catch { # 语法：catch 捕获异常分支
        Write-Host "[ERROR] Failed to stop $EXE_NAME." # 输出停止失败
    }
} # 结束函数 Stop-InputBridge

function Get-Status { # 语法：function 定义函数 Get-Status，查询并显示状态
    Write-Host "[STATUS] Checking $EXE_NAME ..." # 输出正在检查状态
    $bridgeProcess = Get-Process -Name $EXE_NAME -ErrorAction SilentlyContinue # 语法：Get-Process 查询客户端进程
    if ($bridgeProcess) { # 语法：if 判断客户端在运行
        Write-Host "[STATUS] $EXE_NAME is RUNNING" # 输出正在运行
        Write-Host "" # 输出空行
        $bridgeProcess | Format-Table -Property Name, Id, Priority -AutoSize # 语法：管道 | 传给 Format-Table 以表格显示进程信息
    } else { # 语法：else 客户端未运行分支
        Write-Host "[STATUS] $EXE_NAME is NOT RUNNING" # 输出未在运行
    }
    Write-Host "" # 输出空行
    Write-Host "[INFO] Port 27015 forwarding check:" # 输出端口转发检查提示
    Get-NetTCPConnection -LocalPort 27015 -ErrorAction SilentlyContinue | Format-Table -Property LocalAddress, LocalPort, State, OwningProcess -AutoSize # 语法：Get-NetTCPConnection 查询本地端口连接；管道 | 传给 Format-Table 表格显示
} # 结束函数 Get-Status

switch ($command.ToLower()) { # 语法：switch 按命令的小写形式分支；.ToLower() 将字符串转小写
    "start" { # 语法：switch 分支：start 命令
        $gameStarted = Start-Game # 调用启动游戏函数并接收返回值
        if ($gameStarted -or [string]::IsNullOrEmpty($GAME_EXE)) { # 语法：if 判断；-or 逻辑或；游戏启动成功或未配置游戏路径时
            Start-InputBridge # 调用启动输入桥接函数
        }
        break # 语法：break 跳出 switch 语句
    }
    "stop" { # 语法：switch 分支：stop 命令
        Stop-InputBridge # 调用停止输入桥接函数
        break # 语法：break 跳出 switch 语句
    }
    "status" { # 语法：switch 分支：status 命令
        Get-Status # 调用状态查询函数
        break # 语法：break 跳出 switch 语句
    }
    "restart" { # 语法：switch 分支：restart 命令
        Stop-InputBridge # 先停止输入桥接
        Start-Sleep -Seconds 1 # 语法：Start-Sleep 暂停 1 秒
        Start-InputBridge # 再启动输入桥接
        break # 语法：break 跳出 switch 语句
    }
    "help" { # 语法：switch 分支：help 命令
        Show-Help # 调用显示帮助函数
        break # 语法：break 跳出 switch 语句
    }
    default { # 语法：default 默认分支：未知或空命令
        if ([string]::IsNullOrEmpty($command)) { # 语法：if 判断命令为空
            # Default to start
            $gameStarted = Start-Game # 调用启动游戏函数并接收返回值
            if ($gameStarted -or [string]::IsNullOrEmpty($GAME_EXE)) { # 语法：if 判断；-or 逻辑或；无命令时默认启动
                Start-InputBridge # 调用启动输入桥接函数
            }
        } else { # 语法：else 命令非空但未知分支
            Write-Host "[ERROR] Unknown command: $_" # 语法：$_ 表示 switch 当前匹配的值，输出未知命令
            Show-Help # 调用显示帮助函数
        }
        break # 语法：break 跳出 switch 语句
    }
} # 结束 switch 语句
