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

setlocal

set EXE_NAME=inputbridge_client.exe
set EXE_PATH=%~dp0%EXE_NAME%
REM 游戏 EXE 路径（由 SteamLike App 导出时写入；留空则不启动游戏）
set GAME_EXE=__GAME_EXE__

if "%1"=="" goto start
if "%1"=="start" goto start
if "%1"=="stop" goto stop
if "%1"=="status" goto status
if "%1"=="restart" goto restart
if "%1"=="help" goto help
echo [ERROR] Unknown command: %1
goto help

:start
REM ============================================================
REM 1. 先启动游戏 EXE（若已配置路径），成功后再启动输入桥接客户端
REM ============================================================
set GAME_STARTED=0
if "%GAME_EXE%"=="" (
    echo [INFO] No game EXE configured, skip game launch.
) else (
    if exist "%GAME_EXE%" (
        for %%F in ("%GAME_EXE%") do set GAME_PROCESS=%%~nxF
        echo [INFO] Starting game: %GAME_EXE%
        start "" "%GAME_EXE%"
        REM 等待游戏启动并检测进程
        timeout /t 3 /nobreak >NUL
        tasklist /FI "IMAGENAME eq %GAME_PROCESS%" 2>NUL | find /I "%GAME_PROCESS%" >NUL
        if %ERRORLEVEL%==0 (
            echo [OK] Game started: %GAME_PROCESS%
            set GAME_STARTED=1
        ) else (
            echo [WARN] Game process %GAME_PROCESS% not detected after launch.
            echo [INFO] Input bridge will NOT be started.
            goto end
        )
    ) else (
        echo [WARN] Game EXE not found: %GAME_EXE%
        echo [INFO] Please set the correct path in SteamLike App (Windows client page).
        goto end
    )
)
goto end
REM ============================================================
REM 2. 游戏已成功启动后，再启动输入桥接客户端
REM ============================================================
REM Check if already running
tasklist /FI "IMAGENAME eq %EXE_NAME%" 2>NUL | find /I "%EXE_NAME%" >NUL
if %ERRORLEVEL%==0 (
    echo [INFO] %EXE_NAME% is already running.
    goto end
)
REM Check if exe exists
if not exist "%EXE_PATH%" (
    echo [ERROR] %EXE_PATH% not found.
    echo [INFO] Please run build.bat first to compile the exe.
    goto end
)
echo [INFO] Starting %EXE_NAME% ...
echo [INFO] Server: 127.0.0.1:27015
echo [INFO] Opening console window for output ...
start "%EXE_NAME%" "%EXE_PATH%" 127.0.0.1 27015
REM Wait a moment and check if started
timeout /t 2 /nobreak >NUL
tasklist /FI "IMAGENAME eq %EXE_NAME%" 2>NUL | find /I "%EXE_NAME%" >NUL
if %ERRORLEVEL%==0 (
    echo [OK] %EXE_NAME% started successfully.
) else (
    echo [ERROR] Failed to start %EXE_NAME%. It may have exited immediately.
    echo [INFO] Possible cause: another instance holds the single-instance lock.
)
goto end

:stop
tasklist /FI "IMAGENAME eq %EXE_NAME%" 2>NUL | find /I "%EXE_NAME%" >NUL
if not %ERRORLEVEL%==0 (
    echo [INFO] %EXE_NAME% is not running.
    goto end
)
echo [INFO] Stopping %EXE_NAME% ...
taskkill /IM "%EXE_NAME%" /F >NUL 2>&1
if %ERRORLEVEL%==0 (
    echo [OK] %EXE_NAME% stopped.
) else (
    echo [ERROR] Failed to stop %EXE_NAME%.
)
goto end

:status
echo [STATUS] Checking %EXE_NAME% ...
tasklist /FI "IMAGENAME eq %EXE_NAME%" 2>NUL | find /I "%EXE_NAME%" >NUL
if %ERRORLEVEL%==0 (
    echo [STATUS] %EXE_NAME% is RUNNING
    echo.
    tasklist /FI "IMAGENAME eq %EXE_NAME%"
) else (
    echo [STATUS] %EXE_NAME% is NOT RUNNING
)
echo.
echo [INFO] Port 27015 forwarding check:
netstat -ano | findstr "27015"
goto end

:restart
call %0 stop
timeout /t 1 /nobreak >NUL
call %0 start
goto end

:help
echo ========================================
echo   InputBridge Client Control Script
echo ========================================
echo.
echo Usage: control.bat [command]
echo.
echo Commands:
echo   start    Start inputbridge_client.exe
echo   stop     Stop inputbridge_client.exe
echo   status   Show running status and port info
echo   restart  Stop then start
echo   help     Show this help message
echo.
echo Default server: 127.0.0.1:27015
echo.

:end
endlocal
