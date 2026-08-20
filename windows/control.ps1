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

param(
    [Parameter(Mandatory = $false)]
    [string]$command
)

$EXE_NAME = "inputbridge_client.exe"
# 兼容旧版PowerShell获取脚本目录
$scriptPath = $MyInvocation.MyCommand.Definition
$scriptDir = Split-Path -Path $scriptPath -Parent
$EXE_PATH = Join-Path -Path $scriptDir -ChildPath $EXE_NAME
$GAME_EXE = "__GAME_EXE__" # 导出时会被替换为实际游戏路径

function Show-Help {
    Write-Host "========================================"
    Write-Host "  InputBridge Client Control Script"
    Write-Host "========================================"
    Write-Host ""
    Write-Host "Usage: .\control.ps1 [command]"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  start    Start inputbridge_client.exe"
    Write-Host "  stop     Stop inputbridge_client.exe"
    Write-Host "  status   Show running status and port info"
    Write-Host "  restart  Stop then start"
    Write-Host "  help     Show this help message"
    Write-Host ""
    Write-Host "Default server: 127.0.0.1:27015"
    Write-Host ""
}

function Start-Game {
    if ([string]::IsNullOrEmpty($GAME_EXE)) {
        Write-Host "[INFO] No game EXE configured, skip game launch."
        return $true
    }
    if (-not (Test-Path -Path $GAME_EXE)) {
        Write-Host "[WARN] Game EXE not found: $GAME_EXE"
        Write-Host "[INFO] Please set the correct path in SteamLike App (Windows client page)."
        return $false
    }
    $GAME_PROCESS = (Get-Item -Path $GAME_EXE).Name
    Write-Host "[INFO] Starting game: $GAME_EXE"
    Start-Process -FilePath $GAME_EXE -NoNewWindow
    Start-Sleep -Seconds 3
    $gameRunning = Get-Process -Name $GAME_PROCESS -ErrorAction SilentlyContinue
    if ($gameRunning) {
        Write-Host "[OK] Game started: $GAME_PROCESS"
        return $true
    } else {
        Write-Host "[WARN] Game process $GAME_PROCESS not detected after launch."
        Write-Host "[INFO] Input bridge will NOT be started."
        return $false
    }
}

function Start-InputBridge {
    # Check if already running
    $existingProcess = Get-Process -Name $EXE_NAME -ErrorAction SilentlyContinue
    if ($existingProcess) {
        Write-Host "[INFO] $EXE_NAME is already running."
        return
    }
    # Check if exe exists
    if (-not (Test-Path -Path $EXE_PATH)) {
        Write-Host "[ERROR] $EXE_PATH not found."
        Write-Host "[INFO] Please run build.bat first to compile the exe."
        return
    }
    Write-Host "[INFO] Starting $EXE_NAME ..."
    Write-Host "[INFO] Server: 127.0.0.1:27015"
    Write-Host "[INFO] Opening console window for output ..."
    Start-Process -FilePath $EXE_PATH -ArgumentList "127.0.0.1 27015" -NoNewWindow
    Start-Sleep -Seconds 2
    $bridgeRunning = Get-Process -Name $EXE_NAME -ErrorAction SilentlyContinue
    if ($bridgeRunning) {
        Write-Host "[OK] $EXE_NAME started successfully."
    } else {
        Write-Host "[ERROR] Failed to start $EXE_NAME. It may have exited immediately."
        Write-Host "[INFO] Possible cause: another instance holds the single-instance lock."
    }
}

function Stop-InputBridge {
    $existingProcess = Get-Process -Name $EXE_NAME -ErrorAction SilentlyContinue
    if (-not $existingProcess) {
        Write-Host "[INFO] $EXE_NAME is not running."
        return
    }
    Write-Host "[INFO] Stopping $EXE_NAME ..."
    try {
        Stop-Process -Name $EXE_NAME -Force -ErrorAction Stop
        Write-Host "[OK] $EXE_NAME stopped."
    } catch {
        Write-Host "[ERROR] Failed to stop $EXE_NAME."
    }
}

function Get-Status {
    Write-Host "[STATUS] Checking $EXE_NAME ..."
    $bridgeProcess = Get-Process -Name $EXE_NAME -ErrorAction SilentlyContinue
    if ($bridgeProcess) {
        Write-Host "[STATUS] $EXE_NAME is RUNNING"
        Write-Host ""
        $bridgeProcess | Format-Table -Property Name, Id, Priority -AutoSize
    } else {
        Write-Host "[STATUS] $EXE_NAME is NOT RUNNING"
    }
    Write-Host ""
    Write-Host "[INFO] Port 27015 forwarding check:"
    Get-NetTCPConnection -LocalPort 27015 -ErrorAction SilentlyContinue | Format-Table -Property LocalAddress, LocalPort, State, OwningProcess -AutoSize
}

switch ($command.ToLower()) {
    "start" {
        $gameStarted = Start-Game
        if ($gameStarted -or [string]::IsNullOrEmpty($GAME_EXE)) {
            Start-InputBridge
        }
        break
    }
    "stop" {
        Stop-InputBridge
        break
    }
    "status" {
        Get-Status
        break
    }
    "restart" {
        Stop-InputBridge
        Start-Sleep -Seconds 1
        Start-InputBridge
        break
    }
    "help" {
        Show-Help
        break
    }
    default {
        if ([string]::IsNullOrEmpty($command)) {
            # Default to start
            $gameStarted = Start-Game
            if ($gameStarted -or [string]::IsNullOrEmpty($GAME_EXE)) {
                Start-InputBridge
            }
        } else {
            Write-Host "[ERROR] Unknown command: $_"
            Show-Help
        }
        break
    }
}