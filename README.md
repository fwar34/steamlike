# SteamLike 手柄控制器

> Android 手柄 → 键盘/鼠标映射器，专为在 Winlator 中游玩 WoW 乌龟服 1.18.1 设计。
>
> 参考 [InputBridge](https://inputbridge.cloud/) 的架构，采用 **Android TCP服务器 + Windows SendInput客户端** 的桥接方案。
>
> 灵感来自 Steam Input API 的 Action Set Layer 与 Sub-Command 机制，采用 **公共层 + 10个操作层 + 组合键绑定** 架构。
>
> 无需 Root / 无需 Shizuku：通过 **悬浮窗焦点窗口** 直接接收 Android 系统分发的 `KeyEvent` / `MotionEvent`。

---

## 目录

- [项目简介](#项目简介)
- [核心特性](#核心特性)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [Windows配套程序](#windows配套程序)
- [按键映射](#按键映射)
- [组合键绑定](#组合键绑定)
- [10个操作层](#10个操作层)
- [快捷键](#快捷键)
- [悬浮窗 UI](#悬浮窗-ui)
- [运行时配置 API](#运行时配置-api)
- [配置文件](#配置文件)
- [项目结构](#项目结构)
- [入口点](#入口点)
- [线程模型](#线程模型)
- [模块说明](#模块说明)
- [数据结构](#数据结构)
- [测试目录](#测试目录)
- [技术原理](#技术原理)
- [通信协议](#通信协议)
- [依赖说明](#依赖说明)
- [常见问题](#常见问题)

---

## 项目简介

本应用将 Android 手柄输入实时转换为键盘/鼠标事件，通过TCP桥接发送给运行在 Winlator 内的 Windows 配套程序，由 Windows 程序使用 `SendInput()` API 注入到游戏窗口。

**核心创新**:
- **桥接架构**: Android端(TCP服务器) ←→ Windows端(SendInput注入)，参考InputBridge设计
- **公共层 + 操作层**: Steam风格的层叠按键映射，10个操作层可叠加激活，每个层继承公共层并支持单独覆盖
- **组合键绑定(Chord Binding)**: 参考 Steam Input 的 Sub-Command 机制，同一按钮在不同修饰键下可触发不同动作
- **无 Root/无 Shizuku**: 通过悬浮窗焦点窗口直接接收系统 `KeyEvent` / `MotionEvent`，无需任何特权框架

### 适用场景

- 在 Android 设备上通过 Winlator 运行 WoW 乌龟服 1.18.1
- 使用 Xbox/PS/Switch 等手柄进行游戏
- 需要不同场景下的不同按键映射（战斗/骑乘/瞄准/拾取等）

---

## 核心特性

- **桥接注入架构**: Android TCP服务器 + Windows SendInput客户端，参考InputBridge
- **公共层 + 10个操作层**: Steam 风格的层叠按键映射系统
- **组合键绑定(Chord Binding)**: 参考 Steam Sub-Command，同一按钮 + 不同修饰键 = 不同动作
- **配置文件导入/导出**: JSON 格式配置文件，支持导出当前绑定、导入自定义配置、自动持久化
- **焦点窗口捕获手柄**: 通过全屏透明悬浮窗获取焦点，直接接收系统 `KeyEvent`/`MotionEvent`，无需 Root/Shizuku
- **多手柄类型支持**: Xbox/PS/Switch/Steam Controller 自动识别和按键修正
- **摇杆精细控制**: 支持死区(Deadzone)和响应曲线(ResponseCurve)
- **运行时配置**: 可动态修改任意操作层的按键映射
- **悬浮窗 UI**: 可收起/展开的拖动面板，按住层按钮临时激活、松开回公共层
- **震动反馈**: 层切换时的触觉反馈
- **单进程限制**: Windows 客户端使用命名互斥锁确保单实例运行
- **控制脚本**: `control.bat` 提供 start/stop/status/restart 命令
- **内置导出**: APK 内置 exe 和 control.bat，一键导出到 Download/AControler
- **自动重连**: Windows客户端断线自动重连
- **60fps 更新循环**: 流畅的摇杆响应和长按检测

---

## 架构设计

### 整体架构（桥接模式 + 焦点窗口）

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android 设备                              │
│                                                                  │
│  ┌──────────────┐    ┌──────────────────────────────────┐       │
│  │  手柄硬件     │    │  SteamLike APK                   │       │
│  │  (蓝牙/USB)  │───→│                                  │       │
│  └──────────────┘    │  ┌──────────────────────────┐    │       │
│       (系统分发)     │  │ GamepadInputView          │    │       │
│      KeyEvent /      │  │ (全屏透明焦点窗口)         │    │       │
│      MotionEvent ───→│  │ dispatchKeyEvent()        │    │       │
│                      │  │ dispatchGenericMotion()   │    │       │
│                      │  └────────────┬─────────────┘    │       │
│                      │               ↓                  │       │
│                      │  SteamInput (组合键匹配+层栈)     │       │
│                      │  KeyboardMouseMapper (快捷键拦截)│       │
│                      │  BridgeInputInjector (VK映射)    │       │
│                      │               ↓                  │       │
│                      │  InputBridgeServer (TCP:27015)   │───┐   │
│                      └──────────────────────────────────┘   │   │
│                                                              │   │
│  ┌──────────────────────────────────────────────────────────┐│   │
│  │              Winlator (Wine + Box86)                     ││   │
│  │                                                           ││   │
│  │  ┌──────────────────┐  ┌────────────────────┐           ││   │
│  │  │ inputbridge_     │  │     WoW 游戏       │           ││   │
│  │  │ client.exe       │  │  (乌龟服 1.18.1)   │           ││   │
│  │  │                  │  │                    │           ││   │
│  │  │ recv() ←─────────┼──┼→ SendInput() ──────┼───────────┼┘   │
│  │  │ TCP客户端        │  │  注入键鼠事件       │           │    │
│  │  └──────────────────┘  └────────────────────┘           │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 数据流

```
手柄按键 A 按下
     ↓
Android 系统分发 KeyEvent 到前台焦点窗口
     ↓
GamepadInputView.dispatchKeyEvent(event)       ← 全屏透明焦点窗口
     ↓
KeyboardMouseMapper.onKeyEvent(event)          ← 转发
     ↓ interceptButton() 快捷键拦截(LB+方向键等)
     ↓ 未拦截
SteamInput.handleButtonEvent(A, true)
     ↓ 维护 heldButtons 集合 (用于组合键匹配)
     ↓ getEffectiveButtonBinding(A):
     ↓   ① 查找 A 的 chordBindings, 选 chord 最大的匹配
     ↓   ② 未匹配组合键 → 遍历操作层栈的 overrides
     ↓   ③ 仍未覆盖 → 回退到公共层 buttonBindings
     ↓ 假设: A 单独按下 → "Jump" 动作
commonLayer.buttonActions["Jump"].onPressed()
     ↓ 回调: injector.sendKeyDown(KEYCODE_SPACE)
BridgeInputInjector.sendKeyDown()
     ↓ Android KeyCode → Windows VK Code (VK_SPACE=0x20)
InputBridgeServer.sendKeyEvent(0x20, true)
     ↓ TCP 8字节包: [0x01, 0x20, 0x00, 0x01, 0, 0, 0, 0]
     ↓ TCP传输 (localhost:27015)
inputbridge_client.exe (Winlator内)
     ↓ recv() 接收数据包
     ↓ ProcessPacket() 解析
SendInput(INPUT_KEYBOARD, {wVk=VK_SPACE, dwFlags=0})
     ↓
WoW游戏接收 → 角色跳跃!
```

### 公共层 + 操作层架构

```
┌─────────────────────────────────────────────────────────────┐
│                    公共层 (commonLayer)                      │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  动作定义: Jump/Attack/Slot1-0/Loot/Move/Look...    │   │
│  │  默认绑定: A→Jump, B→Interact, X→Attack...          │   │
│  │  回调:     onPressed = { injector.sendKeyPress() }  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           ↑ 继承                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐      │
│  │ Combat   │ │ Mount    │ │ Aim      │ │ Loot     │ ...  │
│  │ 层       │ │ 层       │ │ 层       │ │ 层       │      │
│  │ A→Slot5  │ │ (空覆盖) │ │ Look死区 │ │ A→Loot   │      │
│  │ B→Slot6  │ │          │ │   0.25   │ │ X→TakeAll│      │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘      │
│                                                           │
│  10个操作层, 可同时激活多个, 按激活顺序形成栈               │
└─────────────────────────────────────────────────────────────┘
```

### 按键查找顺序（含组合键）

```
用户按下按钮 A
     ↓
① 检查组合键绑定 chordBindings (公共层)
     ↓ 遍历所有 button == A 的 ChordBinding
     ↓ 过滤: chord 必须是当前 heldButtons 的子集
     ↓ 选择 chordSize 最大的（最具体匹配）
     ↓ 找到? → 使用其 actionName（如 A + RB → "TargetEnemy"）
     ↓ 未找到 ↓
② 从栈顶到栈底遍历活跃操作层，查找 buttonBindingOverrides[A]
     ↓ 找到? → 使用该层的覆盖绑定（如 Combat 层将 A 映射到 "Slot5"）
     ↓ 未找到 ↓
③ 回退到公共层 commonLayer.buttonBindings[A]（如 "Jump"）
```

---

## 快速开始

### 环境要求

- Android 7.0 (API 24) 或更高（推荐 Android 8.0+ 以使用 `TYPE_APPLICATION_OVERLAY`）
- 蓝牙/USB 手柄
- Winlator（用于运行 WoW）
- MinGW gcc（可选，仅自行编译 Windows 配套程序时需要）

### 第一步: Android端安装

1. **编译安装本应用**
   ```bash
   # 在 Android Studio 中打开 l:\steamlike 项目
   # 或使用 Gradle 命令行:
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. **配置权限**
   - 打开 SteamLike 控制器应用
   - 点击"授予悬浮窗权限" → 允许显示在其他应用上层
   - （可选）授予前台服务通知权限（Android 13+）

3. **启动映射**
   - 点击"启动手柄映射"
   - 悬浮窗显示"等待Windows客户端连接... (端口27015)"
   - 焦点输入窗口已自动创建并请求焦点，可接收手柄事件

### 第二步: 获取Windows配套程序

**方式1: 从APK内导出（推荐，无需编译）**

1. 在 SteamLike 控制器主界面点击"导出 Windows 客户端到 Download/AControler"
2. 文件会导出到 `/sdcard/Download/AControler/` 目录：
   - `inputbridge_client.exe` - Windows 客户端程序
   - `control.bat` - 控制脚本（启停管理）
3. 通过文件管理器或 ADB 取出这两个文件

**方式2: 自行编译**

```bash
# 进入windows目录
cd l:\steamlike\windows

# 使用build.bat (需要MinGW)
build.bat

# 或手动编译
gcc -O2 -o inputbridge_client.exe inputbridge_client.c -lws2_32 -luser32
```

### 第三步: 在Winlator中运行

1. **复制程序到Winlator**
   - 将 `inputbridge_client.exe` 和 `control.bat` 复制到 Winlator 的虚拟C盘
   - 通常路径: `Winlator容器内部 → C:\`

2. **启动顺序**
   ```
   ① 打开 SteamLike 控制器 → 授予悬浮窗权限 → 启动手柄映射
   ② 切换到 Winlator → 运行 control.bat start (或直接运行 inputbridge_client.exe)
   ③ 启动 WoW 游戏
   ```

3. **验证连接**
   - Windows客户端控制台显示 `[CONNECT] Connected to Android server!`
   - Android主界面显示 `Client: connected`
   - 按手柄按键，WoW游戏有响应 = 成功
   - 若手柄无响应，请点击悬浮窗区域使焦点窗口重新获取焦点

### 使用流程

```
打开 SteamLike 控制器
      ↓
授予悬浮窗权限
      ↓
点击"启动手柄映射" → 悬浮窗显示"等待连接"
      ↓
切换到 Winlator → 运行 control.bat start
      ↓
客户端显示"[CONNECT] Connected" → 启动 WoW 游戏
      ↓
点击悬浮窗🎮图标展开 → 按住层按钮临时切换操作层
      ↓
（若手柄无响应）点击屏幕使焦点窗口重新获取焦点
```

---

## Windows配套程序

### inputbridge_client.exe

运行在 Winlator 内的 Windows 控制台程序，负责：
1. 连接 Android 端的 TCP 服务器（localhost:27015）
2. 接收 8 字节定长数据包
3. 解析数据包并通过 `SendInput()` API 注入键盘/鼠标事件

### 编译方式

| 方式 | 命令 | 依赖 |
|------|------|------|
| build.bat | `cd windows && build.bat` | MinGW gcc |
| 手动编译 | `gcc -O2 -o inputbridge_client.exe inputbridge_client.c -lws2_32 -luser32` | MinGW gcc |
| CMake | `mkdir build && cd build && cmake .. && make` | CMake + gcc |

### 运行参数

```bash
# 默认连接 127.0.0.1:27015
inputbridge_client.exe

# 指定IP和端口
inputbridge_client.exe 192.168.1.100 27015
```

### 程序特性

- **单进程限制**: 使用命名互斥锁 `Global\SteamLikeInputBridgeClient` 确保同时只有一个实例运行，重复启动提示 `[ERROR] Another instance is already running.`
- **自动重连**: 连接断开后自动重试（默认1秒间隔）
- **状态跟踪**: 跟踪所有按下的键和按钮，断开时自动释放
- **Ctrl+C退出**: 退出时自动释放所有按下的键，防止按键卡住
- **控制台日志**: 全英文输出，显示连接状态和数据流（`[INFO]`/`[CONNECT]`/`[ERROR]`/`[RETRY]`/`[EXIT]`）
- **禁用缓冲**: `setvbuf` 禁用 stdout 缓冲，确保管道/重定向环境下实时输出

### control.bat 控制脚本

| 命令 | 功能 |
|------|------|
| `control.bat start` | 启动 exe（新窗口运行，显示连接输出） |
| `control.bat stop` | 停止 exe（taskkill /F） |
| `control.bat status` | 显示运行状态 + 端口 27015 监听信息 |
| `control.bat restart` | 先 stop 再 start |
| `control.bat help` | 显示帮助 |

---

## 通信协议

### TCP 协议概述

| 属性 | 值 |
|------|-----|
| 传输层 | TCP |
| 服务器 | Android APK (端口 27015) |
| 客户端 | Windows inputbridge_client.exe |
| 数据格式 | 8字节定长包 |
| 字节序 | 小端 (Little-Endian) |

### 消息类型

每个数据包固定8字节，第1字节为消息类型：

| 类型 | 值 | 格式 |
|------|-----|------|
| 键盘事件 | 0x01 | `[0x01][vkCode:u16][isDown:u8][保留:4B]` |
| 鼠标移动 | 0x02 | `[0x02][dx:i16][dy:i16][保留:3B]` |
| 鼠标按钮 | 0x03 | `[0x03][button:u8][isDown:u8][保留:5B]` |
| 鼠标滚轮 | 0x04 | `[0x04][delta:i16][保留:5B]` |
| 释放所有 | 0x05 | `[0x05][保留:7B]` |
| 心跳Ping | 0x06 | `[0x06][保留:7B]` |

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| vkCode | uint16 | Windows虚拟键码 (如 VK_SPACE=0x20) |
| isDown | uint8 | 0=释放, 1=按下 |
| dx/dy | int16 | 鼠标相对位移 |
| button | uint8 | 0=左键, 1=右键, 2=中键 |
| delta | int16 | 滚轮增量 |

### 示例数据包

```
按下空格键:  01 20 00 01 00 00 00 00
释放空格键:  01 20 00 00 00 00 00 00
鼠标右移10px: 02 0A 00 00 00 00 00 00
左键按下:    03 00 01 00 00 00 00 00
释放所有:    05 00 00 00 00 00 00 00
```

---

## 按键映射

### 公共层默认绑定

| 手柄按键 | 动作 | 注入的键盘/鼠标事件 |
|---------|------|-------------------|
| A | Jump | Space (跳跃) |
| B | Interact | 鼠标右键 (互动) |
| X | Attack | T (攻击) |
| Y | Inventory | B (背包) |
| LB | TargetEnemy | Tab (选怪) |
| RB | FaceTarget | F (面向目标) |
| LT | Modifier | Shift (修饰键, 按住) |
| RT | Cast | 鼠标左键 (施法, 按住) |
| L3 (左摇杆按下) | AutoRun | NumLock (自动跑) |
| R3 (右摇杆按下) | Reply | R (回复私聊) |
| MENU | Menu | Esc (菜单) |
| OPTIONS | Chat | Enter (聊天) |
| GUIDE (Home) | Map | M (地图) |
| D-Pad 上 | Slot1 | 1 (快捷栏1) |
| D-Pad 下 | Slot2 | 2 (快捷栏2) |
| D-Pad 左 | Slot3 | 3 (快捷栏3) |
| D-Pad 右 | Slot4 | 4 (快捷栏4) |
| 左摇杆 | Move | WASD (移动) |
| 右摇杆 | Look | 鼠标移动+右键按住 (视角) |

### 摇杆处理

| 摇杆 | 用途 | 处理方式 |
|------|------|---------|
| 左摇杆 | 移动 | 偏移>30% → 按下对应WASD键 |
| 右摇杆 | 视角 | 有输入 → 按住鼠标右键 + 移动鼠标 |
| (第三摇杆) | 光标 | 有输入 → 移动鼠标(不按住键) |

### 摇杆参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| deadzone | 0.15~0.25 | 死区, 中心附近的小幅移动被忽略 |
| responseCurve | 0.5~1.5 | 响应曲线, >1=精细控制, <1=快速响应 |
| lookSensitivity | 15.0 | 右摇杆视角灵敏度(像素/帧) |
| cursorSpeed | 8.0 | 光标移动速度(像素/帧) |

---

## 组合键绑定

> 参考 Steam Input 的 **Sub-Command（子指令）** 机制：同一按钮在不同修饰键状态下可触发不同动作。

### 概念

```
A 单独按下     → Jump       (默认绑定)
A + RB 按住    → TargetEnemy (组合键绑定)
A + RB + LT   → Potion      (更具体的组合键优先)
```

### 匹配规则

1. 按钮按下时，从公共层的 `chordBindings` 中筛选出 `button == 当前按钮` 的所有绑定
2. 过滤掉 `chord` 不是当前 `heldButtons` 子集的绑定
3. 在剩余绑定中，选择 `chord.size` 最大的（**最具体的匹配优先**）
4. 如果没有任何组合键匹配，回退到操作层 overrides → 公共层默认绑定

### WoW 预设组合键

[WoWActionSets.kt](app/src/main/java/com/steamlike/controller/mapping/WoWActionSets.kt) 中已配置以下组合键：

#### RB 作为修饰键（A/B/X/Y + RB）

| 组合键 | 动作 | 注入 |
|--------|------|------|
| A + RB | TargetEnemy | Tab (选怪) |
| B + RB | FaceTarget | F (面向目标) |
| X + RB | Reply | R (回复密语) |
| Y + RB | Map | M (地图) |

#### L3 作为修饰键（D-Pad + L3 → 快捷栏5-8）

| 组合键 | 动作 | 注入 |
|--------|------|------|
| D-Pad↑ + L3 | Slot5 | 5 |
| D-Pad↓ + L3 | Slot6 | 6 |
| D-Pad← + L3 | Slot7 | 7 |
| D-Pad→ + L3 | Slot8 | 8 |

#### R3 作为修饰键（D-Pad + R3 → 快捷栏9/0/-/=）

| 组合键 | 动作 | 注入 |
|--------|------|------|
| D-Pad↑ + R3 | Slot9 | 9 |
| D-Pad↓ + R3 | Slot0 | 0 |
| D-Pad← + R3 | SlotDash | - |
| D-Pad→ + R3 | SlotEqual | = |

### 配置 API

```kotlin
// 在公共层添加组合键绑定
steamInput.commonLayer.addChordBinding(
    button = ControllerButton.A,
    actionName = "TargetEnemy",
    chord = setOf(ControllerButton.RIGHT_SHOULDER)  // A + RB
)

// 多重修饰键（更具体匹配优先）
steamInput.commonLayer.addChordBinding(
    button = ControllerButton.A,
    actionName = "Potion",
    chord = setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER)  // A + RB + LT
)

// 移除某按钮的所有组合键绑定
steamInput.commonLayer.chordBindings
    .filter { it.button == ControllerButton.A }
    .forEach { steamInput.commonLayer.chordBindings.remove(it) }
```

### 注意事项

- 组合键绑定存储在 **公共层**，所有操作层共享（不会因层切换而改变）
- 修饰键本身（如 RB/L3/R3）按下时仍会触发其默认动作（如 RB → FaceTarget）
- 若希望修饰键按下时不触发默认动作，可在 `KeyboardMouseMapper.interceptButton()` 中拦截
- `heldButtons` 由 `SteamInput` 自动维护，按下时加入、释放时移除

---

## 10个操作层

每个操作层默认继承公共层的全部绑定，可单独覆盖任意按键映射。

| # | 名称 | 显示名 | 覆盖内容 |
|---|------|--------|---------|
| 1 | Combat | 战斗 | A/B/X/Y→技能5-8, D-Pad→9/0/-/=, Look响应更快 |
| 2 | Mount | 骑乘 | Move摇杆更灵敏(死区0.15, 曲线1.0) |
| 3 | Aim | 瞄准 | Look摇杆更精准(死区0.25, 曲线0.5), Move更慢 |
| 4 | Loot | 拾取 | A→右键拾取, B→关闭, X→左键全拿 |
| 5 | Stealth | 潜行 | A→潜行, Move更小心(死区0.3, 曲线0.7) |
| 6 | Fishing | 钓鱼 | A→钓鱼, B→关闭拾取 |
| 7 | PvP | 对战 | A/B/X/Y→5-8, D-Pad→9/0/-/=, Look更快 |
| 8 | Raid | 团本 | D-Pad→团队标记1-4 |
| 9 | Travel | 旅行 | A→自动跑, B→坐骑, Move更灵敏 |
| 10 | Custom | 自定义 | 空层(用户运行时配置) |

### 层叠加示例

```
公共层: A → Jump
Combat层: A → Slot5 (激活)
Aim层: (未覆盖A) (激活)

查找A的绑定:
  栈顶 Aim层 → 未覆盖A → 继续
  栈底 Combat层 → 覆盖A为Slot5 → 生效!
  结果: A → Slot5
```

---

## 快捷键

### 手柄快捷键（无需触屏）

| 组合键 | 功能 |
|--------|------|
| LB + D-Pad 上 | 切换 战斗(Combat)层 |
| LB + D-Pad 下 | 切换 骑乘(Mount)层 |
| LB + D-Pad 左 | 切换 瞄准(Aim)层 |
| LB + D-Pad 右 | 切换 拾取(Loot)层 |
| LB + A | 切换 潜行(Stealth)层 |
| LB + B | 切换 钓鱼(Fishing)层 |
| LB + X | 切换 对战(PvP)层 |
| LB + Y | 切换 团本(Raid)层 |
| LB + L3 | 切换 旅行(Travel)层 |
| LB + R3 | 切换 自定义(Custom)层 |
| LB + GUIDE(HOME) | 清除所有层 |

**注意**: LB 按下时会自动释放所有当前按下的按钮，防止切换层时按键卡住。切换层时有震动反馈。

### 悬浮窗 UI

悬浮窗支持**收起/展开**两种状态：

- **收起状态**: 显示一个小 🎮 图标，可拖动移动位置，点击展开
- **展开状态**: 显示完整的操作层面板，包含状态文本、层按钮、控制按钮，可拖动

展开后的操作层按钮（2列×5行网格）采用**按住激活**模式：
- **按住按钮**: 临时激活对应操作层，覆盖公共层绑定
- **松开按钮**: 停用该层，立即回到公共层默认绑定
- 激活的层显示绿色，未激活的层半透明

展开面板还提供"清除层"（清除所有激活层）、"收起"（切换回收起状态）和"关闭"（停止服务）按钮。

---

## 运行时配置 API

### 修改操作层按键映射

在代码中动态修改任意操作层的按键映射：

```kotlin
// 获取 mapper 实例（在 ControllerOverlayService 中）
val mapper = KeyboardMouseMapper(steamInput, injector, screenWidth, screenHeight)

// 将 Custom 层的 A 键映射到 Slot5 动作
mapper.setLayerButtonBinding("Custom", ControllerButton.A, "Slot5")

// 将 Custom 层的 B 键映射到 Slot6 动作
mapper.setLayerButtonBinding("Custom", ControllerButton.B, "Slot6")

// 清除 Custom 层的 A 键覆盖（恢复继承公共层的 Jump）
mapper.clearLayerButtonBinding("Custom", ControllerButton.A)

// 清除 Custom 层的所有覆盖（完全恢复继承公共层）
mapper.clearLayerAllOverrides("Custom")
```

### 直接操作 SteamInput

```kotlin
// 激活操作层
steamInput.activateActionSetLayer("Combat")

// 停用操作层
steamInput.deactivateActionSetLayer("Combat")

// 清除所有操作层
steamInput.deactivateAllLayers()

// 查询激活的层
val activeLayers: List<ActionSetLayer> = steamInput.getActiveLayers()
val isActive: Boolean = steamInput.isLayerActive("Combat")
```

### 创建自定义操作层

```kotlin
steamInput.createActionSetLayer("MyLayer", "我的层") {
    // 覆盖按钮绑定
    overrideButtonBinding(ControllerButton.A, "Slot5")
    overrideButtonBinding(ControllerButton.B, "Slot6")

    // 覆盖摇杆属性
    overrideStick("Look") {
        deadzone = 0.3f
        responseCurve = 0.5f
    }

    // 覆盖扳机属性
    overrideTrigger("Cast") {
        pressThreshold = 0.5f
    }
}
```

---

## 配置文件

> 支持将所有按键绑定、组合键、操作层覆盖导出为 JSON 文件，并在需要时导入恢复。
>
> 配置文件不包含动作定义和回调（这些在代码中定义），只存储**绑定关系**和**属性值**。

### 配置文件格式

```json
{
  "version": 1,
  "name": "WoW默认配置",
  "description": "WoW乌龟服1.18.1默认按键映射",
  "commonLayer": {
    "buttonBindings": {
      "A": "Jump",
      "B": "Interact",
      "X": "Slot1",
      "Y": "Slot2"
    },
    "chordBindings": [
      {
        "button": "A",
        "action": "TargetEnemy",
        "chord": ["RIGHT_SHOULDER"]
      },
      {
        "button": "DPAD_UP",
        "action": "Slot5",
        "chord": ["LEFT_STICK_CLICK"]
      }
    ],
    "stickBindings": {
      "LEFT_STICK": "Move",
      "RIGHT_STICK": "Look"
    },
    "triggerBindings": {
      "LEFT_TRIGGER": "Modifier",
      "RIGHT_TRIGGER": "Cast"
    },
    "stickProperties": {
      "Move": { "deadzone": 0.2, "responseCurve": 1.3 },
      "Look": { "deadzone": 0.15, "responseCurve": 1.5 }
    },
    "triggerProperties": {
      "Modifier": { "pressThreshold": 0.3 },
      "Cast": { "pressThreshold": 0.3 }
    }
  },
  "layers": [
    {
      "name": "Combat",
      "displayName": "战斗模式",
      "buttonBindingOverrides": {
        "A": "Slot5",
        "B": "Slot6",
        "X": "Slot7",
        "Y": "Slot8"
      },
      "stickOverrides": {
        "Look": { "deadzone": 0.2, "responseCurve": 0.8 }
      },
      "triggerOverrides": {}
    },
    {
      "name": "Aim",
      "displayName": "瞄准模式",
      "buttonBindingOverrides": {},
      "stickOverrides": {
        "Look": { "deadzone": 0.25, "responseCurve": 0.5 }
      },
      "triggerOverrides": {}
    }
  ]
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `version` | int | 配置文件版本号（当前=1） |
| `name` | string | 配置名称 |
| `description` | string | 配置描述 |
| `commonLayer` | object | 公共层配置 |
| `commonLayer.buttonBindings` | object | 按钮绑定: 按钮枚举名 → 动作名 |
| `commonLayer.chordBindings` | array | 组合键绑定列表 |
| `commonLayer.stickBindings` | object | 摇杆绑定: 摇杆枚举名 → 动作名 |
| `commonLayer.triggerBindings` | object | 扳机绑定: 扳机枚举名 → 动作名 |
| `commonLayer.stickProperties` | object | 摇杆属性: 动作名 → {deadzone, responseCurve} |
| `commonLayer.triggerProperties` | object | 扳机属性: 动作名 → {pressThreshold} |
| `layers` | array | 操作层配置列表 |
| `layers[].name` | string | 层标识名（如 "Combat"） |
| `layers[].displayName` | string | 显示名（如 "战斗模式"） |
| `layers[].buttonBindingOverrides` | object | 按钮绑定覆盖 |
| `layers[].stickOverrides` | object | 摇杆属性覆盖: 动作名 → 属性 |
| `layers[].triggerOverrides` | object | 扳机属性覆盖: 动作名 → 属性 |

### 按钮枚举名

配置文件中使用枚举名引用按钮/摇杆/扳机，完整列表见 [ControllerTypes.kt](app/src/main/java/com/steamlike/controller/core/ControllerTypes.kt)：

| 枚举名 | 对应手柄按键 |
|--------|-------------|
| `A` `B` `X` `Y` | Xbox A/B/X/Y (PS ×/○/□/△) |
| `DPAD_UP` `DPAD_DOWN` `DPAD_LEFT` `DPAD_RIGHT` | 方向键 |
| `LEFT_SHOULDER` `RIGHT_SHOULDER` | LB / RB |
| `LEFT_TRIGGER` `RIGHT_TRIGGER` | LT / RT |
| `LEFT_STICK_CLICK` `RIGHT_STICK_CLICK` | L3 / R3 (摇杆按下) |
| `GUIDE` | HOME / PS键 |
| `START` `BACK` | Start / Back (Share) |
| `LEFT_STICK` `RIGHT_STICK` | 左/右摇杆 (用于stickBindings) |

### 导出/导入操作

#### 通过 UI 操作（推荐）

在主界面提供三个按钮：

1. **导出配置** - 将当前按键映射保存为 JSON 文件
   - 点击后弹出系统文件选择器（SAF）
   - 选择保存位置，文件名为 `steamlike_config.json`
   - 导出后自动同步到内部存储

2. **导入配置** - 从 JSON 文件加载按键映射
   - 点击后弹出系统文件选择器（SAF）
   - 选择 `.json` 配置文件
   - 导入后自动保存到内部存储，下次启动自动加载

3. **重置为默认配置** - 删除配置文件，恢复 WoW 默认预设

#### 自动持久化

```
服务启动流程:
  ① WoWActionSets.setup() → 加载代码中定义的默认配置
  ② ConfigManager.loadFromFile() → 检查内部配置文件是否存在
     ├─ 存在 → applyConfig() 覆盖默认配置
     └─ 不存在 → 使用默认配置
  ③ setupCommonLayerCallbacks() → 回调不受配置影响
```

内部配置文件路径: `{应用内部存储}/files/steamlike_config.json`

#### 通过代码操作

```kotlin
// 获取 ConfigManager
val configManager = ConfigManager(context)

// === 导出 ===
// 从 SteamInput 提取当前配置
val config = configManager.exportConfig(steamInput, name = "我的配置")
// 保存到内部存储（下次启动自动加载）
configManager.saveToFile(config)
// 或保存到指定 URI（通过 SAF 选择）
configManager.saveToUri(config, uri)

// === 导入 ===
// 从内部存储加载
val config = configManager.loadFromFile()
// 或从指定 URI 加载（通过 SAF 选择）
val config = configManager.loadFromUri(uri)
// 应用到 SteamInput（含验证，跳过未知动作名）
if (config != null) {
    val result = configManager.applyConfig(steamInput, config)
    println("应用 ${result.appliedCount} 项, 跳过 ${result.skippedCount} 项")
    if (result.hasWarnings) {
        result.warnings.forEach { println("警告: $it") }
    }
}

// === 重置 ===
configManager.deleteConfigFile()  // 删除配置文件
```

### 导入验证规则

导入配置时，`ConfigManager.applyConfig()` 会进行以下验证：

1. **按钮名验证**: 检查按钮枚举名是否存在（如 `A`、`RIGHT_SHOULDER`）
2. **动作名验证**: 检查引用的动作是否在代码中已定义（如 `Jump`、`Slot5`）
3. **修饰键验证**: 组合键中的修饰按钮名也需要验证
4. **跳过策略**: 无效的配置项会被跳过（不中断导入），并记录到 `ImportResult.warnings`

### 设计说明

#### 为什么配置文件不包含动作定义？

动作（如 `Jump`、`Interact`）包含回调函数（`onPressed`、`onValueChanged`），这些是可执行代码，无法序列化为 JSON。因此：

- **动作定义和回调**: 在 [WoWActionSets.kt](app/src/main/java/com/steamlike/controller/mapping/WoWActionSets.kt) 和 [KeyboardMouseMapper.kt](app/src/main/java/com/steamlike/controller/mapping/KeyboardMouseMapper.kt) 中用代码定义
- **配置文件**: 只存储"哪个按钮→哪个动作"的绑定关系和属性值
- **导入时**: 动作保持不变，仅修改绑定关系

#### 导入流程

```
1. 清除公共层现有绑定 (buttonBindings/chordBindings/stickBindings/triggerBindings)
2. 清除所有操作层的覆盖 (buttonBindingOverrides/stickOverrides/triggerOverrides)
3. 按配置文件重新设置:
   a. 公共层按钮绑定 (验证按钮名+动作名)
   b. 公共层组合键绑定 (验证按钮名+动作名+修饰键名)
   c. 公共层摇杆/扳机绑定 (验证枚举名+动作名)
   d. 公共层摇杆/扳机属性 (验证动作名)
   e. 操作层覆盖 (已有层更新，新层自动创建)
```

#### 导出流程

```
1. 读取公共层:
   - buttonBindings → buttonBindings (按钮名→动作名)
   - chordBindings → chordBindings (按钮名+动作名+修饰键名列表)
   - stickBindings/triggerBindings → 绑定映射
   - stickActions/triggerActions → 当前属性值 (deadzone/responseCurve/pressThreshold)
2. 读取所有操作层:
   - buttonBindingOverrides → 覆盖映射
   - stickOverrides/triggerOverrides → 属性覆盖值
3. 序列化为 JSON (缩进2空格，UTF-8编码)
```

---

## 项目结构

```
l:\steamlike/
├── settings.gradle.kts              # 项目设置
├── build.gradle.kts                 # 顶层构建配置
├── gradle.properties                # Gradle 属性
├── README.md                        # 项目文档
│
├── app/                             # Android应用
│   ├── build.gradle.kts             # 应用构建配置
│   ├── proguard-rules.pro           # ProGuard 规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # 清单文件(权限+服务声明)
│       │   └── java/com/steamlike/controller/
│       │       ├── App.kt               # Application 入口
│       │       ├── MainActivity.kt      # 主界面(权限管理+配置UI)
│       │       │
│       │       ├── core/                # 核心输入系统
│       │       │   ├── SteamInput.kt          # 主控制器(公共层+操作层+组合键)
│       │       │   ├── ActionSet.kt           # 公共层容器(动作+绑定+chordBindings)
│       │       │   ├── ActionSetLayer.kt      # 操作层(覆盖机制, StickOverride/TriggerOverride)
│       │       │   ├── ChordBinding.kt        # 组合键绑定(Steam Sub-Command)
│       │       │   ├── InputAction.kt         # 动作定义(按钮/扳机/摇杆)
│       │       │   ├── ControllerTypes.kt     # 类型定义(按钮/摇杆/向量)
│       │       │   └── ControllerDevice.kt    # 设备管理+输入映射
│       │       │
│       │       ├── config/              # 配置文件系统
│       │       │   ├── ControllerConfig.kt   # 配置数据模型 + JSON序列化/反序列化
│       │       │   └── ConfigManager.kt      # 导出/导入逻辑 + 文件IO(SAF)
│       │       │
│       │       ├── injection/           # 输入注入(桥接模式)
│       │       │   ├── InputInjector.kt        # 注入器接口
│       │       │   ├── InputBridgeServer.kt    # TCP服务器(端口27015)
│       │       │   ├── BridgeInputInjector.kt  # 桥接注入器(Android→TCP→Windows)
│       │       │   └── GamepadInputView.kt     # 全屏透明焦点窗口(接收系统KeyEvent)
│       │       │
│       │       ├── mapping/             # 按键映射
│       │       │   ├── WoWActionSets.kt        # WoW预设(公共层+10层+组合键)
│       │       │   └── KeyboardMouseMapper.kt  # 手柄→键鼠映射器
│       │       │
│       │       └── service/             # 服务
│       │           └── ControllerOverlayService.kt  # 悬浮窗+焦点窗口前台服务
│       │
│       └── test/                            # ★ 单元测试目录
│           └── java/com/steamlike/controller/
│               ├── config/
│               │   └── ControllerConfigTest.kt  # 配置序列化/反序列化测试
│               └── core/
│                   ├── ActionSetLayerTest.kt  # 操作层覆盖测试
│                   ├── ActionSetTest.kt        # 动作集合容器测试
│                   ├── ChordBindingTest.kt     # 组合键匹配测试
│                   ├── ControllerTypesTest.kt  # 枚举/向量测试
│                   ├── SteamInputTest.kt       # 绑定查找逻辑测试
│                   └── Vector2Test.kt          # 2D向量运算测试
│
└── windows/                         # Windows配套程序
    ├── inputbridge_client.c         # C源码(TCP客户端+SendInput)
    ├── build.bat                    # MinGW编译脚本
    └── CMakeLists.txt               # CMake构建配置
```

### 核心类说明

| 类 | 文件 | 职责 |
|----|------|------|
| `SteamInput` | [SteamInput.kt](app/src/main/java/com/steamlike/controller/core/SteamInput.kt) | 主控制器，管理公共层、操作层栈、组合键匹配，处理输入分发 |
| `ActionSet` | [ActionSet.kt](app/src/main/java/com/steamlike/controller/core/ActionSet.kt) | 公共层容器，定义所有动作、默认绑定、组合键绑定 |
| `ActionSetLayer` | [ActionSetLayer.kt](app/src/main/java/com/steamlike/controller/core/ActionSetLayer.kt) | 操作层，存储对公共层的覆盖 |
| `ChordBinding` | [ChordBinding.kt](app/src/main/java/com/steamlike/controller/core/ChordBinding.kt) | 组合键绑定数据结构，参考 Steam Sub-Command |
| `StickOverride` `TriggerOverride` | [ActionSetLayer.kt](app/src/main/java/com/steamlike/controller/core/ActionSetLayer.kt) | 摇杆/扳机属性覆盖（显式数据类，可序列化） |
| `InputAction` | [InputAction.kt](app/src/main/java/com/steamlike/controller/core/InputAction.kt) | 动作定义（ButtonAction/AnalogTriggerAction/StickPadGyroAction） |
| `ControllerConfig` | [ControllerConfig.kt](app/src/main/java/com/steamlike/controller/config/ControllerConfig.kt) | 配置文件数据模型 + JSON 序列化/反序列化 |
| `ConfigManager` | [ConfigManager.kt](app/src/main/java/com/steamlike/controller/config/ConfigManager.kt) | 导出/导入逻辑 + 文件 I/O（SAF） |
| `WoWActionSets` | [WoWActionSets.kt](app/src/main/java/com/steamlike/controller/mapping/WoWActionSets.kt) | WoW预设配置（公共层 + 10个操作层 + 组合键示例） |
| `KeyboardMouseMapper` | [KeyboardMouseMapper.kt](app/src/main/java/com/steamlike/controller/mapping/KeyboardMouseMapper.kt) | 手柄→键鼠映射器，快捷键拦截，层切换，转发 KeyEvent/MotionEvent |
| `GamepadInputView` | [GamepadInputView.kt](app/src/main/java/com/steamlike/controller/injection/GamepadInputView.kt) | 全屏透明焦点窗口，重写 dispatchKeyEvent/dispatchGenericMotionEvent 捕获手柄事件 |
| `InputBridgeServer` | [InputBridgeServer.kt](app/src/main/java/com/steamlike/controller/injection/InputBridgeServer.kt) | TCP服务器，转发事件到Windows客户端 |
| `BridgeInputInjector` | [BridgeInputInjector.kt](app/src/main/java/com/steamlike/controller/injection/BridgeInputInjector.kt) | 桥接注入器，Android KeyCode→Windows VK Code映射 |
| `inputbridge_client` | [inputbridge_client.c](windows/inputbridge_client.c) | Windows配套程序，TCP客户端+SendInput注入 |
| `ControllerOverlayService` | [ControllerOverlayService.kt](app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt) | 悬浮窗 + 焦点输入窗口前台服务 |
| `App` | [App.kt](app/src/main/java/com/steamlike/controller/App.kt) | Application 入口（用于全局初始化） |
| `MainActivity` | [MainActivity.kt](app/src/main/java/com/steamlike/controller/MainActivity.kt) | 主界面 Activity，权限管理 + 配置 UI |
| `ControllerDevice` | [ControllerDevice.kt](app/src/main/java/com/steamlike/controller/core/ControllerDevice.kt) | 手柄设备信息 + 输入映射工具 |
| `ControllerInputMapper` | [ControllerDevice.kt](app/src/main/java/com/steamlike/controller/core/ControllerDevice.kt) | Android KeyCode/MotionEvent → 统一编码 |
| `WoWConfig` | [WoWActionSets.kt](app/src/main/java/com/steamlike/controller/mapping/WoWActionSets.kt) | WoW 配置数据类（公共层 + 10个操作层映射） |
| `InputInjector` | [InputInjector.kt](app/src/main/java/com/steamlike/controller/injection/InputInjector.kt) | 注入器接口（多态抽象） |
| `MouseButton` | [InputInjector.kt](app/src/main/java/com/steamlike/controller/injection/InputInjector.kt) | 鼠标按钮枚举（LEFT/RIGHT/MIDDLE） |

---

## 入口点

应用有三个核心入口点，分别对应 Application / Activity / Service 三个层级。

### 1. Application 入口: `App.kt`

```kotlin
class App : Application()
```

- **声明位置**: [AndroidManifest.xml](app/src/main/AndroidManifest.xml) 中 `<application android:name=".App">`
- **职责**: 应用级初始化（当前为空实现，预留扩展点）
- **生命周期**: 应用进程启动时创建，进程结束时销毁
- **当前未使用**: 留作未来扩展（如全局异常处理、日志初始化等）

### 2. Activity 入口: `MainActivity.kt`

```kotlin
class MainActivity : AppCompatActivity()
```

- **声明位置**: AndroidManifest.xml 中 `<activity android:name=".MainActivity" android:exported="true">`，包含 `MAIN` / `LAUNCHER` intent-filter
- **职责**:
  - 检查并请求悬浮窗权限（`SYSTEM_ALERT_WINDOW`）
  - 启动/停止 `ControllerOverlayService` 前台服务
  - 提供配置管理 UI（导出/导入/重置）
  - 通过 SAF（Storage Access Framework）选择配置文件
- **UI 构建**: 纯代码构建（无 XML 布局），使用 `ScrollView` + `LinearLayout`
- **与服务的通信**: 通过 `Intent` + `startForegroundService()` 发送动作指令
  - `ACTION_EXPORT_CONFIG` / `ACTION_IMPORT_CONFIG` / `ACTION_RESET_CONFIG` / `ACTION_STOP`
  - 配置文件 URI 通过 `Intent.putExtra(EXTRA_CONFIG_URI, uri)` 传递

### 3. Service 入口: `ControllerOverlayService.kt`

```kotlin
class ControllerOverlayService : Service()
```

- **声明位置**: AndroidManifest.xml 中 `<service android:name=".service.ControllerOverlayService" android:foregroundServiceType="specialUse">`
- **职责**:
  - 作为前台服务持续运行（带通知栏）
  - 创建双窗口（GamepadInputView + 悬浮 UI 面板）
  - 启动 TCP 服务器等待 Windows 客户端
  - 初始化 `SteamInput` + `KeyboardMouseMapper` + `ConfigManager`
  - 处理来自 MainActivity 的配置操作 Intent
- **启动方式**: `ContextCompat.startForegroundService(context, intent)`
- **Android 14+ 要求**:
  - 声明 `FOREGROUND_SERVICE_SPECIAL_USE` 权限
  - 调用 `ServiceCompat.startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`
  - 在 Manifest 中声明 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`

### 4. Windows 端入口: `inputbridge_client.c`

- **入口函数**: C 标准 `main(int argc, char *argv[])`
- **运行环境**: Winlator 内的 Windows 虚拟环境
- **启动参数**: `inputbridge_client.exe [server_ip] [port]`（默认 127.0.0.1:27015）
- **职责**: 连接 Android TCP 服务器，接收 8 字节定长数据包，调用 `SendInput()` API 注入键鼠事件

### 启动流程图

```
用户点击应用图标
      ↓
App.onCreate()                      ← Application 初始化
      ↓
MainActivity.onCreate()             ← 显示主界面
      ↓ 用户点击"启动手柄映射"
ContextCompat.startForegroundService()
      ↓
ControllerOverlayService.onCreate()
      ├─ ServiceCompat.startForeground()  ← 显示通知
      ├─ createOverlay()                  ← 创建悬浮窗 UI
      └─ onStartCommand()
           └─ startMapper() (后台线程)
                ├─ InputBridgeServer.start()  ← TCP 服务器
                ├─ SteamInput(context)         ← 输入系统
                ├─ KeyboardMouseMapper.start() ← 加载 WoW 预设
                ├─ loadUserConfig()            ← 加载用户配置（覆盖默认）
                └─ mainHandler.post { createGamepadInputWindow() }  ← 主线程创建焦点窗口
```

---

## 线程模型

应用涉及多个线程协同工作，关键操作必须放在正确的线程，否则会崩溃或行为异常。

### 线程总览

| 线程 | 创建者 | 职责 | 关键约束 |
|------|--------|------|---------|
| **主线程 (UI Thread)** | Android 系统 | UI 操作、Handler 回调、60fps 更新循环 | 禁止网络操作 |
| **Mapper 后台线程** | `ControllerOverlayService.startMapper()` | TCP 服务器初始化、SteamInput 创建 | 一次性任务 |
| **BridgeServer-Accept** | `InputBridgeServer.start()` | 接受客户端连接 | 阻塞在 `ServerSocket.accept()` |
| **BridgeServer-Dispatch** | `InputBridgeServer.start()` | 从消息队列取数据包发送给客户端 | 阻塞在 `queue.poll()` |
| **BridgeServer-Client-N** | `InputBridgeServer.acceptLoop()` | 监听单个客户端断开（每客户端一线程） | 阻塞在 `input.read()` |

### 线程交互图

```
┌─────────────────────────────────────────────────────────────────┐
│                       主线程 (Main Thread)                       │
│                                                                  │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │ MainActivity    │  │ ControllerOver  │  │ SteamInput      │ │
│  │ UI 事件         │  │ layService      │  │ 60fps 更新循环   │ │
│  │ (按钮点击)      │  │ (UI 操作)       │  │ (Handler.post)  │ │
│  └────────┬────────┘  └────────┬────────┘  └─────────────────┘ │
│           │                    │                                  │
│           │   startForegroundService(intent)                      │
│           ↓                    ↓                                  │
└─────────────────────────────────────────────────────────────────┘
                                │
                  ┌─────────────┴─────────────┐
                  ↓                            ↓
┌───────────────────────────┐  ┌──────────────────────────────────┐
│   Mapper 后台线程          │  │  BridgeServer-Accept 线程        │
│   (一次性任务)             │  │  while: serverSocket.accept()    │
│                            │  │  → 每个客户端启动 Client-N 线程   │
│   InputBridgeServer.start()│  └──────────────────────────────────┘
│   SteamInput(context)      │
│   KeyboardMouseMapper.start│  ┌──────────────────────────────────┐
│   loadUserConfig()         │  │  BridgeServer-Dispatch 线程      │
│                            │  │  while: messageQueue.poll()      │
│   mainHandler.post {       │  │  → client.send(packet)           │
│     createGamepadInputWindow  │  → 转发到所有已连接客户端         │
│   }                        │  └──────────────────────────────────┘
└───────────────────────────┘
```

### 关键线程规则

#### 规则 1: 网络操作必须在子线程

```kotlin
// ❌ 错误: 在主线程执行会抛 NetworkOnMainThreadException
ServerSocket().bind(InetSocketAddress(port))

// ✅ 正确: 在子线程执行
Thread {
    ServerSocket().bind(InetSocketAddress(port))
}.start()
```

- **原因**: Android 禁止主线程执行网络 IO，防止阻塞 UI
- **位置**: [ControllerOverlayService.kt](app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt) `startMapper()` 整体放在 `Thread { ... }.start()` 中

#### 规则 2: UI 操作必须在主线程

```kotlin
// ❌ 错误: 在子线程添加 View 会崩溃
windowManager.addView(gamepadInputView, params)

// ✅ 正确: 通过 Handler 切回主线程
mainHandler.post {
    windowManager.addView(gamepadInputView, params)
}
```

- **原因**: `WindowManager.addView()`、`TextView.setText()`、`Toast.show()` 等都必须在主线程
- **位置**: `ControllerOverlayService.startMapper()` 在子线程完成网络初始化后，通过 `mainHandler.post { createGamepadInputWindow() }` 切回主线程创建窗口

#### 规则 3: 更新循环通过 Handler 在主线程执行

```kotlin
// SteamInput.kt 中的 60fps 更新循环
private fun startUpdateLoop() {
    mainHandler.postDelayed(object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val delta = now - lastUpdateTime
            lastUpdateTime = now
            commonLayer.updateAll(delta)  // 更新所有动作状态
            mainHandler.postDelayed(this, 16)  // ~60fps
        }
    }, 16)
}
```

- **原因**: 输入事件在主线程分发，更新循环与事件分发同线程可避免竞态
- **副作用**: 长时间 `updateAll` 会卡 UI，但当前实现非常轻量（仅遍历少量动作）

#### 规则 4: 线程安全集合

```kotlin
// SteamInput.kt 使用并发安全集合
val actionSetLayers = ConcurrentHashMap<String, ActionSetLayer>()
private val activeLayerStack = CopyOnWriteArrayList<ActionSetLayer>()
private val connectedControllers = ConcurrentHashMap<Int, ControllerDevice>()
private val heldButtons = CopyOnWriteArraySet<ControllerButton>()
```

- **原因**: 输入事件可能在多个线程触发（焦点窗口事件分发线程、主线程的 updateLoop）
- **ConcurrentHashMap**: 高并发读写的层映射表
- **CopyOnWriteArrayList**: 读多写少的活跃层栈（遍历时不会抛 ConcurrentModificationException）

#### 规则 5: TCP 服务器使用消息队列解耦

```kotlin
// InputBridgeServer.kt
private val messageQueue = ConcurrentLinkedQueue<ByteArray>()

// 调用方（任意线程）入队
fun sendKeyEvent(vkCode: Int, isDown: Boolean) {
    val packet = ByteArray(PACKET_SIZE)
    // ... 填充 packet
    messageQueue.add(packet)  // 入队，不阻塞
}

// 分发线程（专用线程）出队并发送
private fun dispatchLoop() {
    while (isRunning.get()) {
        val packet = messageQueue.poll()
        if (packet != null) {
            for (client in clients) {
                client.send(packet)  // 实际网络发送
            }
        } else {
            Thread.sleep(1)  // 避免忙等待
        }
    }
}
```

- **优势**: 调用方（主线程的输入回调）不阻塞，网络发送在专用线程异步执行
- **背压控制**: 如果队列过长会消耗内存，但实际手柄事件频率有限（~60Hz），不会成为瓶颈

---

## 模块说明

应用按职责划分为 5 个核心模块 + 1 个服务模块 + 1 个入口模块。

### 模块依赖关系

```
┌─────────────────────────────────────────────────────────────┐
│                    入口模块 (顶层)                           │
│  App.kt         MainActivity.kt                            │
└─────────────────────────┬───────────────────────────────────┘
                          │ startForegroundService
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                    服务模块 (协调者)                         │
│  ControllerOverlayService.kt                                │
│  - 持有 SteamInput / KeyboardMouseMapper / ConfigManager   │
│  - 管理 WindowManager 双窗口                                │
│  - 处理配置操作 Intent                                      │
└────────┬─────────────────┬──────────────────┬──────────────┘
         │                 │                  │
         ↓                 ↓                  ↓
┌─────────────────┐ ┌──────────────┐ ┌──────────────────┐
│   核心模块       │ │  映射模块     │ │   配置模块        │
│   core/         │ │  mapping/    │ │   config/        │
│                 │ │              │ │                  │
│  SteamInput     │ │ WoWActionSets│ │ ControllerConfig │
│  ActionSet      │ │ KeyboardMouse│ │ ConfigManager    │
│  ActionSetLayer │ │ Mapper       │ │                  │
│  ChordBinding   │ │              │ │                  │
│  InputAction    │ │              │ │                  │
│  ControllerTypes│ │              │ │                  │
│  ControllerDevice│ │              │ │                  │
└────────┬────────┘ └──────┬───────┘ └──────────────────┘
         │                 │
         │  持有引用        │ 使用注入器
         ↓                 ↓
┌─────────────────────────────────────────────────────────────┐
│                    注入模块 (底层)                           │
│  injection/                                                 │
│  InputInjector (接口)                                       │
│  BridgeInputInjector (实现: 通过 TCP 发送到 Windows)        │
│  InputBridgeServer (TCP 服务器)                             │
│  GamepadInputView (焦点窗口, 接收系统手柄事件)              │
└─────────────────────────────────────────────────────────────┘
```

### 各模块详细说明

#### 1. 入口模块 (`com.steamlike.controller`)

| 文件 | 职责 |
|------|------|
| [App.kt](app/src/main/java/com/steamlike/controller/App.kt) | Application 入口，预留全局初始化 |
| [MainActivity.kt](app/src/main/java/com/steamlike/controller/MainActivity.kt) | 主界面，权限管理 + 配置 UI |

#### 2. 服务模块 (`service`)

| 文件 | 职责 |
|------|------|
| [ControllerOverlayService.kt](app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt) | 前台服务，协调所有模块，管理双窗口 |

#### 3. 核心模块 (`core`)

| 文件 | 职责 |
|------|------|
| [SteamInput.kt](app/src/main/java/com/steamlike/controller/core/SteamInput.kt) | 主控制器，管理公共层、操作层栈、组合键匹配、设备监听 |
| [ActionSet.kt](app/src/main/java/com/steamlike/controller/core/ActionSet.kt) | 公共层容器，定义动作和默认绑定，含 60fps 更新循环 |
| [ActionSetLayer.kt](app/src/main/java/com/steamlike/controller/core/ActionSetLayer.kt) | 操作层，存储绑定覆盖和属性覆盖 |
| [ChordBinding.kt](app/src/main/java/com/steamlike/controller/core/ChordBinding.kt) | 组合键绑定数据结构 |
| [InputAction.kt](app/src/main/java/com/steamlike/controller/core/InputAction.kt) | 动作抽象基类，三种子类型（按钮/扳机/摇杆） |
| [ControllerTypes.kt](app/src/main/java/com/steamlike/controller/core/ControllerTypes.kt) | 枚举定义（按钮/摇杆/扳机/手柄类型）+ Vector2 + ControllerState |
| [ControllerDevice.kt](app/src/main/java/com/steamlike/controller/core/ControllerDevice.kt) | 设备信息 + ControllerInputMapper（键码映射工具） |

#### 4. 映射模块 (`mapping`)

| 文件 | 职责 |
|------|------|
| [WoWActionSets.kt](app/src/main/java/com/steamlike/controller/mapping/WoWActionSets.kt) | WoW 预设配置（公共层 + 10 操作层 + 组合键示例） |
| [KeyboardMouseMapper.kt](app/src/main/java/com/steamlike/controller/mapping/KeyboardMouseMapper.kt) | 手柄→键鼠映射器，快捷键拦截，层切换 |

#### 5. 配置模块 (`config`)

| 文件 | 职责 |
|------|------|
| [ControllerConfig.kt](app/src/main/java/com/steamlike/controller/config/ControllerConfig.kt) | 配置数据模型 + JSON 序列化/反序列化 |
| [ConfigManager.kt](app/src/main/java/com/steamlike/controller/config/ConfigManager.kt) | 导出/导入逻辑 + 文件 IO（内部存储 + SAF） |

#### 6. 注入模块 (`injection`)

| 文件 | 职责 |
|------|------|
| [InputInjector.kt](app/src/main/java/com/steamlike/controller/injection/InputInjector.kt) | 注入器接口 + MouseButton 枚举 |
| [BridgeInputInjector.kt](app/src/main/java/com/steamlike/controller/injection/BridgeInputInjector.kt) | 桥接注入器实现，Android KeyCode → Windows VK Code |
| [InputBridgeServer.kt](app/src/main/java/com/steamlike/controller/injection/InputBridgeServer.kt) | TCP 服务器，端口 27015，8 字节定长包协议 |
| [GamepadInputView.kt](app/src/main/java/com/steamlike/controller/injection/GamepadInputView.kt) | 全屏透明焦点窗口，捕获系统 KeyEvent/MotionEvent |

---

## 数据结构

### 核心数据结构总览

```
SteamInput
  ├─ commonLayer: ActionSet                         ← 公共层（唯一）
  │    ├─ buttonActions: Map<String, ButtonAction>       ← 按钮动作定义
  │    ├─ triggerActions: Map<String, AnalogTriggerAction>  ← 扳机动作定义
  │    ├─ stickActions: Map<String, StickPadGyroAction>  ← 摇杆动作定义
  │    ├─ buttonBindings: Map<ControllerButton, String>  ← 按钮绑定
  │    ├─ chordBindings: List<ChordBinding>              ← 组合键绑定
  │    ├─ stickBindings: Map<ControllerStick, String>    ← 摇杆绑定
  │    └─ triggerBindings: Map<ControllerTrigger, String> ← 扳机绑定
  │
  ├─ actionSetLayers: ConcurrentHashMap<String, ActionSetLayer>  ← 所有已注册层
  ├─ activeLayerStack: CopyOnWriteArrayList<ActionSetLayer>      ← 活跃层栈
  ├─ connectedControllers: ConcurrentHashMap<Int, ControllerDevice>  ← 已连接手柄
  ├─ currentStates: ConcurrentHashMap<Int, ControllerState>          ← 输入状态快照
  └─ heldButtons: CopyOnWriteArraySet<ControllerButton>             ← 当前按住的按钮
```

### 枚举类型

#### `ControllerButton` - 标准手柄按键（跨平台统一）

```kotlin
enum class ControllerButton {
    A, B, X, Y,                                           // 面部按钮
    LEFT_SHOULDER, RIGHT_SHOULDER,                        // 肩键 LB/RB
    LEFT_TRIGGER_CLICK, RIGHT_TRIGGER_CLICK,              // 扳机点击 L2/R2
    LEFT_STICK_CLICK, RIGHT_STICK_CLICK,                   // 摇杆按下 L3/R3
    MENU, OPTIONS, GUIDE,                                 // 菜单/选项/Home键
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,            // 十字键
    TOUCHPAD_CLICK                                        // 触控板点击
}
```

- **跨平台**: 无论 Xbox/PS/Switch，都统一映射到此枚举
- **PS 手柄修正**: PS 的 ×/○ 与 Xbox 的 A/B 位置互换，由 `ControllerInputMapper` 自动修正

#### `ControllerStick` - 摇杆类型

```kotlin
enum class ControllerStick {
    LEFT_STICK,        // 左摇杆
    RIGHT_STICK,       // 右摇杆
    DPAD_AS_STICK      // 十字键作为摇杆（兼容无左摇杆的设备）
}
```

#### `ControllerTrigger` - 扳机类型

```kotlin
enum class ControllerTrigger {
    LEFT_TRIGGER,   // 左扳机 (LT/L2/ZL)
    RIGHT_TRIGGER   // 右扳机 (RT/R2/ZR)
}
```

#### `ControllerType` - 手柄类型（用于按键修正）

通过 USB Vendor ID / Product ID 识别：

| 类型 | Vendor ID | Product ID |
|------|-----------|------------|
| XBOX_360 | 0x045E | 0x028E |
| XBOX_ONE | 0x045E | 0x02DD |
| XBOX_ELITE | 0x045E | 0x0B00 |
| PS3 | 0x054C | 0x0268 |
| PS4 | 0x054C | 0x05C4 |
| PS5_DUALSENSE | 0x054C | 0x0CE6 |
| SWITCH_PRO | 0x057E | 0x2009 |
| STEAM_CONTROLLER | 0x28DE | 0x1102 |
| STEAM_DECK | 0x28DE | 0x1205 |
| GENERIC | -1 | null |

#### `InputActionType` - 动作类型

```kotlin
enum class InputActionType {
    BUTTON,           // 按钮（二进制）
    ANALOG_TRIGGER,   // 模拟扳机（0.0~1.0）
    STICK_PAD_GYRO    // 摇杆/触控板/陀螺仪（2D 向量）
}
```

#### `MouseButton` - 鼠标按钮

```kotlin
enum class MouseButton { LEFT, RIGHT, MIDDLE }
```

### 数据类

#### `InputAction` (sealed class)

三种子类型，每种对应一种输入形态：

```kotlin
sealed class InputAction {
    data class ButtonAction(           // 按钮动作
        val name: String,
        var onPressed: (() -> Unit)?,  // 按下回调
        var onReleased: (() -> Unit)?, // 释放回调
        var onUpdate: ((isHeld: Boolean, heldTimeMs: Long) -> Unit)?,  // 每帧回调
        var isPressed: Boolean,        // 当前是否按下
        var heldTimeMs: Long           // 按住时长（ms）
    )

    data class AnalogTriggerAction(    // 扳机动作
        val name: String,
        var pressThreshold: Float,     // 按压阈值（默认 0.5）
        var onValueChanged: ((value: Float) -> Unit)?,
        var onPressed: (() -> Unit)?,
        var onReleased: (() -> Unit)?,
        var currentValue: Float,       // 当前值（0.0~1.0）
        var isPressed: Boolean
    )

    data class StickPadGyroAction(     // 摇杆动作
        val name: String,
        var deadzone: Float,           // 死区（默认 0.15）
        var responseCurve: Float,      // 响应曲线（默认 1.0）
        var onValueChanged: ((vector: Vector2) -> Unit)?,
        var onDirectionChanged: ((direction: StickDirection) -> Unit)?,
        var clickAction: ButtonAction?,
        var currentValue: Vector2,     // 处理后的值
        var rawValue: Vector2          // 原始值
    ) {
        enum class StickDirection {    // 8方向 + 中心
            CENTER, UP, UP_RIGHT, RIGHT, DOWN_RIGHT,
            DOWN, DOWN_LEFT, LEFT, UP_LEFT
        }
    }
}
```

#### `ChordBinding` - 组合键绑定

```kotlin
data class ChordBinding(
    val button: ControllerButton,      // 触发按钮
    val actionName: String,            // 动作名称
    val chord: Set<ControllerButton>   // 修饰按钮集合（空=默认绑定）
) {
    fun matches(heldButtons: Set<ControllerButton>): Boolean  // chord 是否为 heldButtons 子集
    val chordSize: Int  // chord.size，用于优先级排序
}
```

#### `ActionSetLayer` - 操作层

```kotlin
class ActionSetLayer(
    val name: String,                  // 层标识名
    val displayName: String            // 显示名
) {
    val buttonBindingOverrides: MutableMap<ControllerButton, String>  // 按钮绑定覆盖
    val stickOverrides: MutableMap<String, StickOverride>             // 摇杆属性覆盖
    val triggerOverrides: MutableMap<String, TriggerOverride>         // 扳机属性覆盖
    var onActivated: (() -> Unit)?
    var onDeactivated: (() -> Unit)?
    internal var stackPosition: Int    // 在栈中的位置（-1=未激活）
}

data class StickOverride(
    var deadzone: Float?,              // null=不覆盖
    var responseCurve: Float?
)

data class TriggerOverride(
    var pressThreshold: Float?
)
```

#### `Vector2` - 2D 向量

```kotlin
data class Vector2(
    val x: Float = 0f,                 // X 轴（右为正）
    val y: Float = 0f                  // Y 轴（下为正，与 Android 屏幕坐标一致）
) {
    val magnitude: Float               // 向量长度（0.0~1.0）
    fun normalized(): Vector2          // 归一化
    fun withDeadzone(deadzone: Float): Vector2  // 应用死区
    companion object { val ZERO = Vector2(0f, 0f) }
}
```

#### `ControllerDevice` - 手柄设备信息

```kotlin
data class ControllerDevice(
    val deviceId: Int,                 // Android 设备 ID
    val name: String,                  // 设备名称
    val controllerType: ControllerType,// 手柄类型
    val inputDevice: InputDevice,      // Android InputDevice
    val supportsVibration: Boolean,    // 是否支持震动
    val hasLeftStick: Boolean,         // 是否有左摇杆
    val hasRightStick: Boolean,        // 是否有右摇杆
    val hasAnalogTriggers: Boolean,    // 是否有模拟扳机
    val hasDpad: Boolean               // 是否有十字键
)
```

#### `ControllerState` - 输入状态快照

```kotlin
data class ControllerState(
    val deviceId: Int,
    val timestamp: Long,
    val buttons: Map<ControllerButton, Boolean>,    // 按钮状态
    val sticks: Map<ControllerStick, Vector2>,      // 摇杆位置
    val triggers: Map<ControllerTrigger, Float>     // 扳机值
)
```

### 配置文件数据模型

```
ControllerConfig (根)
  ├─ version: Int                      ← 配置文件版本号（当前=1）
  ├─ name: String                      ← 配置名称
  ├─ description: String               ← 配置描述
  ├─ commonLayer: CommonLayerConfig    ← 公共层配置
  │    ├─ buttonBindings: Map<String, String>       ← 按钮名 → 动作名
  │    ├─ chordBindings: List<ChordBindingConfig>   ← 组合键列表
  │    ├─ stickBindings: Map<String, String>        ← 摇杆名 → 动作名
  │    ├─ triggerBindings: Map<String, String>      ← 扳机名 → 动作名
  │    ├─ stickProperties: Map<String, StickPropertiesConfig>     ← 摇杆属性
  │    └─ triggerProperties: Map<String, TriggerPropertiesConfig> ← 扳机属性
  │
  └─ layers: List<LayerConfig>         ← 操作层配置列表
       ├─ name: String                 ← 层标识名
       ├─ displayName: String          ← 显示名
       ├─ buttonBindingOverrides: Map<String, String>
       ├─ stickOverrides: Map<String, StickPropertiesConfig>
       └─ triggerOverrides: Map<String, TriggerPropertiesConfig>

ChordBindingConfig:
  ├─ button: String      ← 触发按钮枚举名
  ├─ action: String      ← 动作名
  └─ chord: List<String> ← 修饰按钮枚举名列表

StickPropertiesConfig:
  ├─ deadzone: Float?       ← null=不覆盖
  └─ responseCurve: Float?

TriggerPropertiesConfig:
  └─ pressThreshold: Float?

ImportResult (导入结果):
  ├─ appliedCount: Int    ← 成功应用数
  ├─ skippedCount: Int    ← 跳过数
  └─ warnings: List<String> ← 警告信息
```

### 设计模式说明

#### 1. **数据类与回调分离**

- **数据类**（`ControllerConfig`、`StickOverride`）只存储数据，可序列化
- **回调**（`onPressed`、`onValueChanged`）在代码中定义，不可序列化
- 因此配置文件只包含绑定关系和属性值，不包含动作行为

#### 2. **密封类（sealed class）多态**

`InputAction` 使用密封类表示三种动作类型，编译器会检查 `when` 表达式的完整性，避免遗漏新增类型。

#### 3. **覆盖模式（Override Pattern）**

操作层不替换公共层，而是通过 `overrides` 增量覆盖：
- 查找时从栈顶到栈底遍历，第一个找到的覆盖生效
- 未覆盖的按键回退到公共层默认绑定

#### 4. **接口抽象（多态注入）**

`InputInjector` 接口允许不同的注入实现：
- 当前实现: `BridgeInputInjector`（通过 TCP 桥接到 Windows）
- 可扩展: 未来可添加 `LocalInputInjector`（直接注入 Android 系统）

---

## 测试目录

### 测试覆盖

应用在 `app/src/test/` 目录下提供完整的单元测试，使用 JUnit 4 + 纯 JVM 运行（无需 Android 设备/模拟器）。

### 测试文件清单

| 文件 | 测试内容 | 用例数 |
|------|---------|--------|
| [Vector2Test.kt](app/src/test/java/com/steamlike/controller/core/Vector2Test.kt) | 2D 向量运算（magnitude/normalized/withDeadzone） | ~10 |
| [ControllerTypesTest.kt](app/src/test/java/com/steamlike/controller/core/ControllerTypesTest.kt) | 枚举解析（ControllerButton/ControllerType.fromVendorProduct） | ~8 |
| [ChordBindingTest.kt](app/src/test/java/com/steamlike/controller/core/ChordBindingTest.kt) | 组合键匹配逻辑（matches/chordSize） | ~10 |
| [ActionSetTest.kt](app/src/test/java/com/steamlike/controller/core/ActionSetTest.kt) | 动作集合容器（注册/更新循环/状态重置/8方向计算） | ~15 |
| [ActionSetLayerTest.kt](app/src/test/java/com/steamlike/controller/core/ActionSetLayerTest.kt) | 操作层覆盖机制（绑定覆盖/属性覆盖/多层栈） | ~12 |
| [SteamInputTest.kt](app/src/test/java/com/steamlike/controller/core/SteamInputTest.kt) | 绑定查找逻辑（组合键匹配/层覆盖/栈优先级） | ~14 |
| [ControllerConfigTest.kt](app/src/test/java/com/steamlike/controller/config/ControllerConfigTest.kt) | 配置 JSON 序列化/反序列化（往返测试/枚举解析） | ~20 |

### 测试策略

#### 1. 纯 JVM 测试（无 Android 依赖）

由于 `SteamInput` 构造需要 Android `Context`（获取 `InputManager`），测试通过以下方式绕过：

```kotlin
// SteamInputTest.kt 中模拟核心查找逻辑
private fun lookupBinding(
    button: ControllerButton,
    heldButtons: Set<ControllerButton>,
    commonLayer: ActionSet,           // 直接构造 ActionSet，无需 SteamInput
    activeLayerStack: List<ActionSetLayer>
): String? {
    // 复制 SteamInput.getEffectiveButtonBinding 的算法
    // 1. 检查组合键绑定（chordSize 最大的匹配优先）
    // 2. 从栈顶到栈底查找层覆盖
    // 3. 回退到公共层默认绑定
}
```

- **优势**: 测试可在任何 JVM 环境运行，无需 Robolectric 或模拟器
- **代价**: 测试逻辑需与生产代码保持同步（算法变更需同步更新测试）

#### 2. 配置文件往返测试

```kotlin
@Test
fun `往返测试 (Round-trip)`() {
    val original = ControllerConfig(
        version = 1,
        name = "往返测试",
        commonLayer = CommonLayerConfig(
            buttonBindings = mapOf("A" to "Jump", "B" to "Interact"),
            chordBindings = listOf(ChordBindingConfig("A", "Slot5", listOf("RIGHT_SHOULDER")))
        ),
        layers = listOf(LayerConfig("Combat", "战斗", mapOf("A" to "Slot5")))
    )

    val json = original.toJsonString(2)        // 序列化
    val parsed = parseConfig(json)              // 反序列化

    assertEquals(original.version, parsed.version)
    assertEquals(original.commonLayer.buttonBindings, parsed.commonLayer.buttonBindings)
    assertEquals(original.layers.size, parsed.layers.size)
}
```

- 验证序列化 + 反序列化的数据一致性
- 覆盖所有字段（按钮绑定/组合键/摇杆属性/扳机属性/操作层覆盖）

### 运行测试

```bash
# 运行所有单元测试
./gradlew test

# 运行特定测试类
./gradlew test --tests "com.steamlike.controller.core.SteamInputTest"

# 运行特定测试方法
./gradlew test --tests "com.steamlike.controller.core.SteamInputTest.组合键在修饰键按住时触发"
```

### 测试依赖

```kotlin
// app/build.gradle.kts
dependencies {
    // ... 实现依赖 ...

    // 单元测试依赖
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")  // JSON 解析（替代 Android org.json）
}
```

> **说明**: 测试使用 `org.json:json:20240303`（标准 Java 实现）替代 Android 内置的 `org.json`，因为单元测试运行在纯 JVM 环境，无法访问 Android API。

---

## 技术原理

### 1. 焦点窗口捕获手柄事件（无 Root/无 Shizuku）

Android 系统会将手柄的 `KeyEvent` / `MotionEvent` 分发给**当前持有焦点的窗口**。本应用创建一个**全屏透明、不可触摸**的悬浮窗（`TYPE_APPLICATION_OVERLAY`），并使其获得焦点，从而直接接收系统分发的手柄事件：

```
手柄硬件 → Android InputManager → 焦点窗口
                                      ↓
                          GamepadInputView (全屏透明, FLAG_NOT_TOUCHABLE)
                                      ↓
                          dispatchKeyEvent() / dispatchGenericMotionEvent()
                                      ↓
                          转发到 KeyboardMouseMapper → SteamInput
```

关键点：
- `FLAG_NOT_TOUCHABLE`: 窗口不拦截触摸事件，触摸会穿透到下层应用（不影响 Winlator 操作）
- `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`: 全屏覆盖
- `isFocusable = true; isFocusableInTouchMode = true`: 可获取焦点
- 通过 `requestFocus()` 主动获取焦点；失去焦点时（如切换应用）需重新请求

### 2. 双窗口架构

```
┌─────────────────────────────────────────┐
│  Window 1: GamepadInputView (全屏透明)   │  ← 接收手柄事件
│  FLAG_NOT_TOUCHABLE                      │  ← 触摸穿透
│  isFocusable = true                      │  ← 持有焦点
└─────────────────────────────────────────┘
                  ↓ (事件转发)
┌─────────────────────────────────────────┐
│  Window 2: Floating UI Panel (悬浮面板)  │  ← 显示状态、提供层切换按钮
│  TYPE_APPLICATION_OVERLAY                │  ← 可触摸交互
└─────────────────────────────────────────┘
```

### 3. 摇杆死区和响应曲线

```
原始输入 (MotionEvent AXIS_X/AXIS_Y, -1.0~1.0)
      ↓
应用死区: magnitude < deadzone → 归零
      ↓
应用响应曲线: magnitude^responseCurve
  - responseCurve > 1: 前半段更慢(精细控制, 适合瞄准)
  - responseCurve < 1: 前半段更快(快速响应, 适合战斗)
  - responseCurve = 1: 线性
      ↓
触发 onValueChanged 回调
```

### 4. 操作层栈管理

```
激活 Combat → 栈: [Combat]
激活 Aim    → 栈: [Combat, Aim]
查找按钮A:
  Aim(栈顶) → 未覆盖 → 继续
  Combat    → 覆盖A=Slot5 → 生效

停用 Combat → 栈: [Aim]
查找按钮A:
  Aim(栈顶) → 未覆盖 → 回退到公共层 → A=Jump
```

### 5. 组合键匹配（Steam Sub-Command）

```
按下按钮 A
  ↓
heldButtons 集合 (当前按住的所有按钮, 不含 A)
  ↓
遍历公共层 chordBindings, 筛选 button == A 的绑定
  ↓
过滤: chord 必须是 heldButtons 的子集
  ↓
选择 chordSize 最大的（最具体的匹配）
  ↓
  ├─ 找到 → 使用其 actionName
  └─ 未找到 → 走操作层 overrides → 公共层默认绑定

示例:
  heldButtons = {RB}
  A 的 chordBindings: [{A, Jump, {}}, {A, TargetEnemy, {RB}}]
  → 匹配 {A, TargetEnemy, {RB}} (chordSize=1)
  → 结果: A → TargetEnemy
```

---

## 依赖说明

| 依赖 | 版本 | 用途 |
|------|------|------|
| AndroidX Core KTX | 1.12.0 | AndroidX核心扩展 |
| AndroidX AppCompat | 1.6.1 | 向后兼容支持 |
| Material Components | 1.11.0 | Material Design组件 |
| ConstraintLayout | 2.1.4 | 布局 |

> **无外部特权依赖**：本应用不依赖 Shizuku、HiddenApiBypass、Root 等任何特权框架，仅使用 Android 公开 API + 标准AndroidX库。

### 权限说明

| 权限 | 用途 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 显示悬浮窗 + 全屏透明焦点窗口 |
| `FOREGROUND_SERVICE` | 前台服务(保持映射运行) |
| `FOREGROUND_SERVICE_SPECIAL_USE` (Android 14+) | 前台服务类型声明 |
| （可选）`POST_NOTIFICATIONS` (Android 13+) | 前台服务通知 |

---

## 常见问题

### Q: 手柄按键完全无响应

**A**: 焦点窗口可能失去了焦点。请：
1. 点击屏幕任意位置（使全屏透明焦点窗口重新获取焦点）
2. 确认 Android 端悬浮窗显示"客户端已连接"
3. 确认 Windows 端显示"已连接到Android服务器"
4. 确认手柄已被 Android 系统识别（设置 → 蓝牙/设备）
5. 若仍无效，尝试停止并重新启动"手柄映射"服务

### Q: 为什么需要点击屏幕才能用手柄？

**A**: Android 系统只将手柄事件分发给**持有焦点的窗口**。当切换应用（如从 SteamLike 切到 Winlator）时，焦点会转移到 Winlator，此时本应用的 `GamepadInputView` 失去焦点。
解决方式：触摸事件穿透设计（`FLAG_NOT_TOUCHABLE`）让点击落到 Winlator 的同时，本应用的透明窗口也能借机重新获得焦点（具体取决于系统焦点策略）。

### Q: 悬浮窗显示"等待Windows客户端连接"

**A**: 这是正常状态，表示Android端TCP服务器已启动，等待Winlator内的 `inputbridge_client.exe` 连接。请：
1. 确认已编译 `inputbridge_client.exe`（运行 `windows/build.bat`）
2. 将exe复制到Winlator的C盘
3. 在Winlator中运行 `inputbridge_client.exe`
4. 连接成功后悬浮窗会显示"客户端已连接"

### Q: inputbridge_client.exe 显示"连接失败"

**A**: 可能原因：
1. Android端未启动服务（先在Android端点击"启动手柄映射"）
2. 端口被占用（检查27015端口）
3. Winlator网络隔离（尝试指定Android设备IP: `inputbridge_client.exe 192.168.1.100`）

### Q: 按键卡住不释放

**A**: 
- 切换操作层时系统会自动释放所有按键
- Windows客户端断开时会自动释放所有按键
- 如果仍有问题，关闭Windows客户端再重新打开

### Q: 如何修改按键映射？

**A**: 四种方式：
1. **修改源码**: 编辑 [WoWActionSets.kt](app/src/main/java/com/steamlike/controller/mapping/WoWActionSets.kt) 中的公共层绑定或操作层覆盖
2. **运行时API**: 调用 `mapper.setLayerButtonBinding(layerName, button, actionName)`
3. **修改回调**: 编辑 [KeyboardMouseMapper.kt](app/src/main/java/com/steamlike/controller/mapping/KeyboardMouseMapper.kt) 中 `setupCommonLayerCallbacks()` 的回调设置
4. **配置文件**: 导出当前配置 → 编辑 JSON → 导入。详见 [配置文件](#配置文件) 章节

### Q: 如何添加组合键绑定？

**A**: 在 [WoWActionSets.kt](app/src/main/java/com/steamlike/controller/mapping/WoWActionSets.kt) 的 `setupCommonLayer()` 中调用：
```kotlin
c.addChordBinding(
    button = ControllerButton.A,
    actionName = "TargetEnemy",
    chord = setOf(ControllerButton.RIGHT_SHOULDER)  // A + RB
)
```
或在运行时调用 `steamInput.commonLayer.addChordBinding(...)`。详见 [组合键绑定](#组合键绑定) 章节。

### Q: 支持哪些手柄？

**A**: 支持 Xbox 360/One/Elite、PS3/4/5、Switch Pro、Steam Controller 等主流手柄。通过 USB Vendor/Product ID 自动识别，并修正按键映射差异。

### Q: 如何添加新的操作层？

**A**: 在 [WoWActionSets.kt](app/src/main/java/com/steamlike/controller/mapping/WoWActionSets.kt) 中：
1. 在 `LAYER_NAMES` 列表添加层名
2. 创建 `createXxxLayer(steamInput)` 方法
3. 在 `setup()` 中调用

### Q: 配置文件导入后部分项无效？

**A**: 导入配置时会验证按钮名和动作名，无效项会被跳过。可能原因：
1. **按钮名拼写错误**: 如 `"A"` 写成 `"a"` 或 `"ButtonA"`（需使用枚举名，区分大小写）
2. **动作名不存在**: 配置引用了代码中未定义的动作名（如 `"NewAction"`）
3. **修饰键名错误**: 组合键中的修饰按钮名无效
4. 查看 `ImportResult.warnings` 了解具体哪些项被跳过

### Q: 导入配置后动作的回调还在吗？

**A**: 在。配置文件只修改**绑定关系**（哪个按钮→哪个动作）和**属性值**（死区/响应曲线等），不修改动作定义和回调。导入后所有动作的 `onPressed`/`onValueChanged` 回调保持不变。

### Q: 如何手动编辑配置文件？

**A**:
1. 在 App 中点击"导出配置"，保存 JSON 文件到设备
2. 用文本编辑器打开 JSON 文件，修改绑定关系
3. 点击"导入配置"，选择修改后的 JSON 文件
4. 配置自动保存到内部存储，下次启动自动加载

### Q: 配置文件存在哪里？

**A**:
- **内部配置**: `{应用内部存储}/files/steamlike_config.json`（自动加载，无需用户干预）
- **导出位置**: 由用户通过 SAF（系统文件选择器）选择，可保存到下载目录、外部存储等任意位置

### Q: 重置配置后还能恢复吗？

**A**: "重置为默认配置"只删除内部配置文件，恢复为代码中定义的 WoW 默认预设。如果你之前导出过配置文件，可以通过"导入配置"重新加载。

### Q: 与旧版（基于 Shizuku）的差异？

**A**: 新版完全移除了 Shizuku / HiddenApiBypass 依赖，改为通过全屏透明焦点窗口接收系统 `KeyEvent` / `MotionEvent`：
1. **无需 Root/无需 Shizuku**: 任何用户开箱即用
2. **事件来源更可靠**: 直接使用 Android 系统已归一化的事件，无需解析 `getevent` 原始字节
3. **新增组合键绑定**: 参考 Steam Sub-Command，支持修饰键下的多动作映射
4. **限制**: 焦点窗口需要持续持有焦点，切换应用后可能需要点击屏幕恢复

---

## 开发说明

### 编译要求

- Android Studio Hedgehog 或更高
- JDK 8
- Android SDK 34 (compileSdk)
- 最低支持 Android 7.0 (minSdk 24)

### 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 安装到设备
./gradlew installDebug
```

### 调试技巧

- 悬浮窗的 `statusText` 显示当前状态
- `layerText` 显示当前激活的操作层堆栈
- 可在 `KeyboardMouseMapper` 的回调中添加日志
- 可在 `GamepadInputView.dispatchKeyEvent()` 中添加日志查看原始 KeyEvent
- `SteamInput.heldButtons` 可观察当前按住的按钮（用于调试组合键匹配）
- 导入配置后查看 `ImportResult.warnings` 了解哪些项被跳过及原因
- 内部配置文件路径: `adb shell run-as com.steamlike.controller cat files/steamlike_config.json`
- 配置加载日志: 搜索 Logcat 标签 `ConfigManager`

---

## 许可证

本项目仅供学习和个人使用。

## 致谢

- [InputBridge](https://inputbridge.cloud/) - TCP 桥接架构参考
- [Steam Input API](https://partner.steamgames.com/doc/features/steam_controller) - Action Set Layer / Sub-Command 架构灵感来源
