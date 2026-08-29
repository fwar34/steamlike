REM 语法：@echo off 关闭命令回显，使脚本只显示输出而不显示每条命令本身
@echo off
REM ========================================
REM  InputBridge Client 编译脚本
REM  需要安装 MinGW (gcc)
REM ========================================

REM 语法：echo 输出编译流程开始提示的分隔线
echo ========================================
REM 语法：echo 输出编译目标名称
echo  编译 InputBridge Client
REM 语法：echo 输出分隔线
echo ========================================

REM 检查gcc是否可用
REM 语法：where 在 PATH 中查找 gcc 命令；>nul 2>nul 丢弃所有输出，只关心退出码
where gcc >nul 2>nul
REM 语法：if neq 判断退出码不等于 0（未找到 gcc 时）
if %errorlevel% neq 0 (
REM 语法：echo 输出未找到 gcc 的错误提示
    echo [错误] 未找到gcc, 请安装MinGW或添加到PATH
REM 语法：echo 输出下载地址
    echo 下载地址: https://www.mingw-w64.org/
REM 语法：pause 暂停脚本并等待用户按键
    pause
REM 语法：exit /b 1 退出脚本并返回退出码 1；/b 仅退出当前批处理
    exit /b 1
REM 语法：) 结束 if 块
)

REM 语法：echo 输出开始编译客户端源码的提示
echo [编译] inputbridge_client.c ...
REM 语法：gcc 调用编译器；-O2 开启优化；-o 指定输出文件名；-lws2_32 -luser32 链接 Winsock 与 user32 库
gcc -O2 -o inputbridge_client.exe inputbridge_client.c -lws2_32 -luser32

REM 语法：if neq 判断退出码不等于 0（编译失败时）
if %errorlevel% neq 0 (
REM 语法：echo 输出编译失败的错误提示
    echo [错误] 编译失败
REM 语法：pause 暂停脚本并等待用户按键
    pause
REM 语法：exit /b 1 退出脚本并返回退出码 1
    exit /b 1
REM 语法：) 结束 if 块
)

REM 语法：echo 输出编译成功的提示
echo [成功] 已生成 inputbridge_client.exe
REM 语法：echo. 输出空行
echo.
REM 语法：echo 输出使用方法标题
echo 使用方法:
REM 语法：echo 输出使用步骤 1
echo   1. 将 inputbridge_client.exe 复制到 Winlator 的 C盘
REM 语法：echo 输出使用步骤 2
echo   2. 在 Android 端启动 SteamLike 手柄控制器
REM 语法：echo 输出使用步骤 3
echo   3. 在 Winlator 命令行运行: inputbridge_client.exe
REM 语法：echo 输出使用步骤 4
echo   4. 保持窗口打开, 切到WoW游戏即可
REM 语法：echo. 输出空行
echo.
REM 语法：pause 暂停脚本，等待用户按键后关闭窗口
pause
