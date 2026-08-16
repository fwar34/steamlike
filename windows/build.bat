@echo off
REM ========================================
REM  InputBridge Client 编译脚本
REM  需要安装 MinGW (gcc)
REM ========================================

echo ========================================
echo  编译 InputBridge Client
echo ========================================

REM 检查gcc是否可用
where gcc >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未找到gcc, 请安装MinGW或添加到PATH
    echo 下载地址: https://www.mingw-w64.org/
    pause
    exit /b 1
)

echo [编译] inputbridge_client.c ...
gcc -O2 -o inputbridge_client.exe inputbridge_client.c -lws2_32 -luser32

if %errorlevel% neq 0 (
    echo [错误] 编译失败
    pause
    exit /b 1
)

echo [成功] 已生成 inputbridge_client.exe
echo.
echo 使用方法:
echo   1. 将 inputbridge_client.exe 复制到 Winlator 的 C盘
echo   2. 在 Android 端启动 SteamLike 手柄控制器
echo   3. 在 Winlator 命令行运行: inputbridge_client.exe
echo   4. 保持窗口打开, 切到WoW游戏即可
echo.
pause
