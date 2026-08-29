REM 语法：@echo off 关闭命令回显，使脚本只显示输出而不显示每条命令本身
@echo off
REM ====================================================================
REM InputBridge Client Control Script
REM
REM Usage:
REM   control.bat start    - Start inputbridge_client.exe
REM   control.bat stop     - Stop inputbridge_client.exe
REM   control.bat status   - Show running status
REM   control.bat restart  - Restart inputbridge_client.exe
REM   control.bat help     - Show this help
REM
REM Note: Run build.bat first to compile the exe.
REM ====================================================================

REM 语法：setlocal 开始局部环境变量作用域，脚本结束时自动恢复调用前的环境
setlocal

REM 语法：set 定义变量 EXE_NAME 为客户端可执行文件名
set EXE_NAME=inputbridge_client.exe
REM 语法：set 定义变量 EXE_PATH；%~dp0 表示脚本所在目录；拼接出 exe 的完整路径
set EXE_PATH=%~dp0%EXE_NAME%
REM 游戏 EXE 路径（由 SteamLike App 导出时写入；留空则不启动游戏）
REM 语法：set 定义游戏路径变量，导出时由 SteamLike App 将占位符替换为实际路径
set GAME_EXE=__GAME_EXE__

REM 语法：if 判断第 1 个参数为空，为空则跳转到 :start 标签（默认启动）
if "%1"=="" goto start
REM 语法：if 判断参数为 start 则跳转到启动流程
if "%1"=="start" goto start
REM 语法：if 判断参数为 stop 则跳转到停止流程
if "%1"=="stop" goto stop
REM 语法：if 判断参数为 status 则跳转到状态查询流程
if "%1"=="status" goto status
REM 语法：if 判断参数为 restart 则跳转到重启流程
if "%1"=="restart" goto restart
REM 语法：if 判断参数为 help 则跳转到帮助信息流程
if "%1"=="help" goto help
REM 语法：echo 输出未知命令错误信息；%1 引用第 1 个命令行参数
echo [ERROR] Unknown command: %1
REM 语法：goto 跳转到 :help 标签显示帮助
goto help

REM 语法：标签 :start，启动流程的入口
:start
REM ============================================================
REM 1. 先启动游戏 EXE（若已配置路径），成功后再启动输入桥接客户端
REM ============================================================
REM 语法：set 定义游戏启动标记变量，0=未启动，1=已启动
set GAME_STARTED=0
REM 语法：if 判断游戏路径为空则进入括号块
if "%GAME_EXE%"=="" (
REM 语法：echo 提示未配置游戏路径，跳过启动游戏
    echo [INFO] No game EXE configured, skip game launch.
REM 语法：) else ( 结束 if 块并开始 else 分支
) else (
REM 语法：if exist 判断游戏文件是否存在，存在则进入括号块
    if exist "%GAME_EXE%" (
REM 语法：for %%F 遍历匹配到的文件，%%~nxF 提取文件名和扩展名存入变量 GAME_PROCESS
        for %%F in ("%GAME_EXE%") do set GAME_PROCESS=%%~nxF
REM 语法：echo 输出正在启动游戏的提示及路径
        echo [INFO] Starting game: %GAME_EXE%
REM 语法：start 启动游戏程序；"" 表示使用空窗口标题
        start "" "%GAME_EXE%"
        REM 等待游戏启动并检测进程
REM 语法：timeout 等待 3 秒；/nobreak 忽略按键中断；>NUL 丢弃输出
        timeout /t 3 /nobreak >NUL
REM 语法：tasklist 列出进程并管道给 find 匹配游戏进程名；2>NUL 丢弃错误输出
        tasklist /FI "IMAGENAME eq %GAME_PROCESS%" 2>NUL | find /I "%GAME_PROCESS%" >NUL
REM 语法：if 判断上一条命令的退出码是否为 0（%ERRORLEVEL% 取退出码；0 表示找到进程）
        if %ERRORLEVEL%==0 (
REM 语法：echo 输出游戏启动成功的提示
            echo [OK] Game started: %GAME_PROCESS%
REM 语法：set 将游戏启动标记置为 1
            set GAME_STARTED=1
REM 语法：) else ( 结束 if 块并开始 else 分支
        ) else (
REM 语法：echo 警告启动后未检测到游戏进程
            echo [WARN] Game process %GAME_PROCESS% not detected after launch.
REM 语法：echo 提示输入桥接将不会被启动
            echo [INFO] Input bridge will NOT be started.
REM 语法：goto 跳转到 :end 标签结束脚本
            goto end
REM 语法：) 结束 if 的 else 分支
        )
REM 语法：) else ( 结束 if exist 块并开始 else 分支
    ) else (
REM 语法：echo 警告游戏可执行文件不存在
        echo [WARN] Game EXE not found: %GAME_EXE%
REM 语法：echo 提示在 SteamLike App 的 Windows 客户端页面设置正确路径
        echo [INFO] Please set the correct path in SteamLike App (Windows client page).
REM 语法：goto 跳转到 :end 标签结束脚本
        goto end
REM 语法：) 结束 else 分支
    )
REM 语法：) 结束最外层的 if 块
)

REM ============================================================
REM 2. 游戏已成功启动后，再启动输入桥接客户端
REM ============================================================
REM Check if already running
REM 语法：tasklist 列出进程并管道给 find 匹配客户端进程名，判断是否已在运行
tasklist /FI "IMAGENAME eq %EXE_NAME%" 2>NUL | find /I "%EXE_NAME%" >NUL
REM 语法：if 判断退出码为 0（已运行）
if %ERRORLEVEL%==0 (
REM 语法：echo 提示客户端已在运行
    echo [INFO] %EXE_NAME% is already running.
REM 语法：goto 跳转到 :end 标签结束脚本
    goto end
REM 语法：) 结束 if 块
)
REM Check if exe exists
REM 语法：if not exist 判断客户端 exe 文件不存在则进入括号块
if not exist "%EXE_PATH%" (
REM 语法：echo 输出错误：客户端文件不存在
    echo [ERROR] %EXE_PATH% not found.
REM 语法：echo 提示先运行 build.bat 编译出 exe
    echo [INFO] Please run build.bat first to compile the exe.
REM 语法：goto 跳转到 :end 标签结束脚本
    goto end
REM 语法：) 结束 if 块
)
REM 语法：echo 输出开始启动客户端的提示
echo [INFO] Starting %EXE_NAME% ...
REM 语法：echo 输出服务器地址与端口信息
echo [INFO] Server: 127.0.0.1:27015
REM 语法：echo 提示将打开控制台窗口显示输出
echo [INFO] Opening console window for output ...
REM 语法：start 启动客户端；第一个带引号参数为窗口标题，随后为程序路径和命令行参数
start "%EXE_NAME%" "%EXE_PATH%" 127.0.0.1 27015
REM Wait a moment and check if started
REM 语法：timeout 等待 2 秒；>NUL 丢弃输出
timeout /t 2 /nobreak >NUL
REM 语法：tasklist 管道 find 检查客户端是否已启动成功
tasklist /FI "IMAGENAME eq %EXE_NAME%" 2>NUL | find /I "%EXE_NAME%" >NUL
REM 语法：if 判断退出码为 0（启动成功）
if %ERRORLEVEL%==0 (
REM 语法：echo 输出客户端启动成功
    echo [OK] %EXE_NAME% started successfully.
REM 语法：) else ( 结束 if 块并开始 else 分支
) else (
REM 语法：echo 输出启动失败提示（可能立即退出了）
    echo [ERROR] Failed to start %EXE_NAME%. It may have exited immediately.
REM 语法：echo 提示可能被单实例锁占用
    echo [INFO] Possible cause: another instance holds the single-instance lock.
REM 语法：) 结束 else 分支
)
REM 语法：goto 跳转到 :end 标签结束脚本
goto end

REM 语法：标签 :stop，停止流程的入口
:stop
REM 语法：tasklist 管道 find 检查客户端是否在运行
tasklist /FI "IMAGENAME eq %EXE_NAME%" 2>NUL | find /I "%EXE_NAME%" >NUL
REM 语法：if not 判断退出码不为 0（客户端未在运行）
if not %ERRORLEVEL%==0 (
REM 语法：echo 提示客户端未在运行
    echo [INFO] %EXE_NAME% is not running.
REM 语法：goto 跳转到 :end 标签结束脚本
    goto end
REM 语法：) 结束 if 块
)
REM 语法：echo 输出开始停止客户端的提示
echo [INFO] Stopping %EXE_NAME% ...
REM 语法：taskkill 按映像名强制结束进程；/IM 指定映像名；/F 强制；>NUL 2>&1 丢弃所有输出
taskkill /IM "%EXE_NAME%" /F >NUL 2>&1
REM 语法：if 判断退出码为 0（停止成功）
if %ERRORLEVEL%==0 (
REM 语法：echo 输出停止成功
    echo [OK] %EXE_NAME% stopped.
REM 语法：) else ( 结束 if 块并开始 else 分支
) else (
REM 语法：echo 输出停止失败
    echo [ERROR] Failed to stop %EXE_NAME%.
REM 语法：) 结束 else 分支
)
REM 语法：goto 跳转到 :end 标签结束脚本
goto end

REM 语法：标签 :status，状态查询流程的入口
:status
REM 语法：echo 输出正在检查客户端状态的提示
echo [STATUS] Checking %EXE_NAME% ...
REM 语法：tasklist 管道 find 检查客户端是否在运行
tasklist /FI "IMAGENAME eq %EXE_NAME%" 2>NUL | find /I "%EXE_NAME%" >NUL
REM 语法：if 判断退出码为 0（客户端在运行）
if %ERRORLEVEL%==0 (
REM 语法：echo 输出客户端正在运行
    echo [STATUS] %EXE_NAME% is RUNNING
REM 语法：echo. 输出一个空行
    echo.
REM 语法：tasklist 列出客户端进程的详细信息
    tasklist /FI "IMAGENAME eq %EXE_NAME%"
REM 语法：) else ( 结束 if 块并开始 else 分支
) else (
REM 语法：echo 输出客户端未在运行
    echo [STATUS] %EXE_NAME% is NOT RUNNING
REM 语法：) 结束 else 分支
)
REM 语法：echo. 输出空行
echo.
REM 语法：echo 输出端口转发检查的提示
echo [INFO] Port 27015 forwarding check:
REM 语法：netstat 显示网络连接信息；管道 findstr 过滤包含端口 27015 的行
netstat -ano | findstr "27015"
REM 语法：goto 跳转到 :end 标签结束脚本
goto end

REM 语法：标签 :restart，重启流程的入口
:restart
REM 语法：call 调用本脚本自身并传 stop 参数（先停止）
call %0 stop
REM 语法：timeout 等待 1 秒；>NUL 丢弃输出
timeout /t 1 /nobreak >NUL
REM 语法：call 调用本脚本自身并传 start 参数（再启动）
call %0 start
REM 语法：goto 跳转到 :end 标签结束脚本
goto end

REM 语法：标签 :help，帮助信息流程的入口
:help
REM 语法：echo 输出帮助信息标题分隔线
echo ========================================
REM 语法：echo 输出帮助信息标题
echo   InputBridge Client Control Script
REM 语法：echo 输出帮助信息标题分隔线
echo ========================================
REM 语法：echo. 输出空行
echo.
REM 语法：echo 输出用法说明
echo Usage: control.bat [command]
REM 语法：echo. 输出空行
echo.
REM 语法：echo 输出命令列表标题
echo Commands:
REM 语法：echo 输出 start 命令说明
echo   start    Start inputbridge_client.exe
REM 语法：echo 输出 stop 命令说明
echo   stop     Stop inputbridge_client.exe
REM 语法：echo 输出 status 命令说明
echo   status   Show running status and port info
REM 语法：echo 输出 restart 命令说明
echo   restart  Stop then start
REM 语法：echo 输出 help 命令说明
echo   help     Show this help message
REM 语法：echo. 输出空行
echo.
REM 语法：echo 输出默认服务器信息
echo Default server: 127.0.0.1:27015
REM 语法：echo. 输出空行
echo.

REM 语法：标签 :end，脚本统一结束入口
:end
REM 语法：endlocal 结束局部环境变量作用域，恢复调用前的环境
endlocal
