# SteamLike Controller — 线程模型与类图（UML）

> 本文档用 Mermaid 绘制本项目（Android 手柄映射桥接 App + Windows 注入程序）的线程模型与类图。
> 支持 Mermaid 的 Markdown 渲染器（VS Code、GitHub、Typora 等）可查看图表。

---

## 一、线程模型

### 1. 全局线程划分

```mermaid
flowchart LR
    subgraph Main["① 主线程 (Main/UI Thread — Looper.getMainLooper)"]
        V["GamepadInputView<br/><b>焦点输入窗口</b><br/>View 事件分发 + IME 输入"]
        M["KeyboardMouseMapper<br/><b>事件入口 + 映射执行</b><br/>onKeyEvent / onGenericMotionEvent"]
        SI["SteamInput<br/><b>核心控制</b><br/>按键解析·层切换·回调分发"]
        H["mainHandler<br/>controllerMonitor(2s 轮询手柄状态)<br/>UI 刷新 / 状态广播"]
        SVQ["InputBridgeServer<br/><b>enqueue()</b><br/>事件打包入队(线程安全队列)"]
    end

    subgraph BG["② 后台线程"]
        B1["startMapper 启动线程<br/>(匿名 Thread)<br/>ServerSocket.bind → 初始化注入器/核心"]
        B2["BridgeServer-Accept<br/>acceptLoop 接受客户端"]
        B3["BridgeServer-Dispatch<br/>dispatchLoop 队列→所有客户端"]
        B4["BridgeServer-Client-N<br/>handleClient 逐连接检测断开"]
        B5["SteamLike-LookLoop<br/>(守护线程 8ms 固定频率)<br/>右摇杆视角发送"]
        B6["SteamLike-SmartMonitor<br/>(守护线程 1.5s 轮询)<br/>智能暂停监控"]
    end

    subgraph WIN["③ Windows / Winlator 侧"]
        W["InputBridgeClient.exe<br/>recv() → SendInput() → WoW"]
    end

    V -- "dispatchKeyEvent / dispatchGenericMotionEvent" --> M
    M -- "dispatchKeyEvent / dispatchGenericMotionEvent" --> SI
    SI -- "onButtonMapped / onStickMapped 回调" --> M
    M -- "sendKeyDown / sendMouseXxx" --> SVQ
    SVQ -- "入队 ConcurrentLinkedQueue" --> B3
    B3 -- "8 字节定长包 (TCP 27015)" --> W

    M -- "写入 latestLookX/Y (主线程)" --> B5
    B5 -- "sendMouseMove(dx,dy)" --> SVQ

    B6 -- "mainHandler.post { 暂停/恢复捕获 }" --> H
    B1 -- "mainHandler.post { createGamepadInputWindow() }" --> H
    SI -- "mainHandler.post { UI 更新 }" --> H
```

**说明**：所有 View 事件、映射执行、注入器调用都在主线程完成；`InputBridgeServer` 的网络收发在独立后台线程；右摇杆视角用专用守护线程按 8ms 固定频率发送，保证包节奏均匀。

### 2. 按键事件流（按钮按下/释放）

```mermaid
sequenceDiagram
    autonumber
    participant HW as 手柄硬件
    participant IM as Android InputManager
    participant V as GamepadInputView<br/>(主线程)
    participant M as KeyboardMouseMapper<br/>(主线程)
    participant SI as SteamInput<br/>(主线程)
    participant INJ as BridgeInputInjector<br/>(主线程)
    participant SV as InputBridgeServer<br/>(主线程入队)
    participant DISP as BridgeServer-Dispatch<br/>(后台线程)
    participant WIN as InputBridgeClient.exe<br/>(Winlator)

    HW->>IM: 按键事件
    IM->>V: dispatchKeyEvent()
    V->>M: onKeyEvent(event)
    M->>SI: dispatchKeyEvent(event)
    SI->>SI: handleButtonEvent()<br/>解析映射（激活层 → 公共层）
    alt 命中 SwitchLayer
        SI->>SI: 激活/停用对应操作层<br/>onLayerChanged → 悬浮窗刷新
    else 命中键鼠映射
        SI-->>M: onButtonMapped(button, pressed, mapping)
        M->>INJ: sendKeyDown / sendKeyUp(vkCode)
        INJ->>SV: server.sendKeyEvent(vkCode, down)
        SV->>DISP: 打包入队 messageQueue
        DISP->>WIN: 发送 8 字节协议包
        WIN->>WIN: SendInput() 注入 WoW
    end
```

### 3. 右摇杆视角流（事件 → 固定频率发送）

```mermaid
sequenceDiagram
    autonumber
    participant V as GamepadInputView<br/>(主线程)
    participant SI as SteamInput<br/>(主线程)
    participant M as KeyboardMouseMapper<br/>(主线程)
    participant LOOK as SteamLike-LookLoop<br/>(后台线程 8ms)
    participant SV as InputBridgeServer<br/>(主线程入队)
    participant WIN as InputBridgeClient.exe

    V->>SI: dispatchGenericMotionEvent(右摇杆)
    SI-->>M: onStickMapped(stick, x, y)
    M->>M: 更新 latestLookX / latestLookY
    Note over M,LOOK: 事件线程只写状态；发送由 LookLoop 消费
    loop 每 8ms 一个 tick
        LOOK->>LOOK: processLookTick(dt)<br/>幅值钳制 → 加速曲线 → EMA 平滑 → 位移积分
        LOOK->>SV: sendMouseMove(dx, dy)
        SV->>WIN: TCP 鼠标移动包
        WIN->>WIN: SendInput() 移动视角
    end
```

### 4. 关键线程同步手段

```mermaid
flowchart TB
    Q["messageQueue<br/>ConcurrentLinkedQueue&lt;ByteArray&gt;<br/>（入队主线程 / 出队 Dispatch 线程）"]
    C["clients<br/>CopyOnWriteArrayList&lt;ClientConnection&gt;<br/>（多线程读、写时复制）"]
    V1["latestLookX / latestLookY<br/>（主线程写 / LookLoop 线程读，float 原子性）"]
    H1["mainHandler<br/>（跨线程安全地切回主线程执行 UI 更新）"]
    A1["AtomicBoolean isRunning / AtomicInteger NEXT_ID<br/>（服务器运行标志 / 连接自增ID）"]
```

---

## 二、类图

### 1. 核心类关系

```mermaid
classDiagram
    class ControllerOverlayService {
        -steamInput: SteamInput?
        -mapper: KeyboardMouseMapper?
        -bridgeServer: InputBridgeServer?
        -injector: BridgeInputInjector?
        -gamepadInputView: GamepadInputView?
        -configManager: ConfigManager?
        -smartMonitorThread: Thread?
        +onCreate() Unit
        +onStartCommand(intent, flags, startId) Int
        +pauseCapturing() Unit
        +resumeCapturing() Unit
    }
    ControllerOverlayService *-- GamepadInputView
    ControllerOverlayService --> SteamInput
    ControllerOverlayService --> KeyboardMouseMapper
    ControllerOverlayService --> InputBridgeServer
    ControllerOverlayService --> BridgeInputInjector
    ControllerOverlayService --> ConfigManager

    class SteamInput {
        +profile: ControllerProfile
        +isCapturing: Boolean
        +onButtonMapped: Callback
        +onStickMapped: Callback
        +onLayerChanged: Callback
        +onControllerConnected: Callback
        +dispatchKeyEvent(event) Boolean
        +dispatchGenericMotionEvent(event) Boolean
        +handleButtonEvent(button, pressed) Unit
    }
    SteamInput o-- ControllerProfile
    SteamInput o-- ControllerDevice
    SteamInput ..> ControllerButton

    class KeyboardMouseMapper {
        -lookThread: Thread?
        -latestLookX: Float
        -latestLookY: Float
        +start() Boolean
        +stop() Unit
        +onKeyEvent(event) Boolean
        +onGenericMotionEvent(event) Boolean
        +onLayerChanged: Callback
    }
    KeyboardMouseMapper --> SteamInput
    KeyboardMouseMapper --> InputInjector

    class InputInjector {
        <<interface>>
        +isAvailable() Boolean
        +sendKeyDown(keyCode) Unit
        +sendKeyUp(keyCode) Unit
        +sendMouseMove(dx, dy) Unit
        +sendMouseDown(button) Unit
        +sendMouseUp(button) Unit
        +sendMouseScroll(delta) Unit
        +releaseAll() Unit
    }
    InputInjector <|-- BridgeInputInjector
    BridgeInputInjector --> InputBridgeServer

    class InputBridgeServer {
        -serverSocket: ServerSocket?
        -messageQueue: ConcurrentLinkedQueue
        -clients: CopyOnWriteArrayList
        +start() Boolean
        +stop() Unit
        +sendKeyEvent(vkCode, isDown) Unit
        +sendMouseMove(dx, dy) Unit
        +sendMouseButton(button, isDown) Unit
        +sendMouseWheel(delta) Unit
        +sendReleaseAll() Unit
    }
    InputBridgeServer *-- ClientConnection

    class ClientConnection {
        -socket: Socket
        +id: Int
        +send(data) Unit
        +close() Unit
    }

    class GamepadInputView {
        +onKeyEvent: Callback
        +onGenericMotion: Callback
        +onToggleOverlay: Callback
        +onImeChar: Callback
        +onImeKey: Callback
        +dispatchKeyEvent(event) Boolean
    }

    class ConfigManager {
        +saveProfile() Unit
        +loadProfile() ControllerProfile
    }
    ConfigManager --> ControllerConfig
    ConfigManager --> SteamInput

    class ControllerConfig {
        <<object>>
        +toJson(profile) String
        +fromJson(json) ControllerProfile
    }
```

### 2. 数据模型（配置结构）

```mermaid
classDiagram
    class ControllerProfile {
        +commonLayer: OperationLayer
        +layers: List&lt;OperationLayer&gt;
        +globalSettings: GlobalSettings
    }
    ControllerProfile *-- "1" OperationLayer
    ControllerProfile *-- "10" OperationLayer
    ControllerProfile *-- GlobalSettings

    class OperationLayer {
        +name: String
        +buttonMappings: MutableMap&lt;ControllerButton, KeyMapping&gt;
    }
    OperationLayer *-- KeyMapping

    class KeyMapping {
        +action: MappedAction
        +subCommands: List&lt;Int&gt;
    }
    KeyMapping --> MappedAction

    class GlobalSettings {
        +deadzone: Float
        +lookSensitivity: Float
        +cursorSpeed: Float
        +lookSmoothing: Float
        +lookAcceleration: Float
    }

    class MappedAction {
        <<sealed>>
    }
    class KeyboardKey
    class MouseClick
    class MouseToggle
    class SwitchLayer
    class MouseMove
    class LookAround
    class MouseScrollUp
    class MouseScrollDown
    class ToggleOverlay
    class ToggleKeyboard
    class ToggleCapture
    MappedAction <|-- KeyboardKey
    MappedAction <|-- MouseClick
    MappedAction <|-- MouseToggle
    MappedAction <|-- SwitchLayer
    MappedAction <|-- MouseMove
    MappedAction <|-- LookAround
    MappedAction <|-- MouseScrollUp
    MappedAction <|-- MouseScrollDown
    MappedAction <|-- ToggleOverlay
    MappedAction <|-- ToggleKeyboard
    MappedAction <|-- ToggleCapture
    MouseClick ..> MouseButton
    MouseToggle ..> MouseButton

    class WoWActionSets {
        <<object>>
        +LAYER_NAMES
    }
    WoWActionSets ..> ControllerProfile : 生成默认预设
```

### 3. UI 层与辅助类

```mermaid
classDiagram
    class App {
        <<Application>>
    }

    class MainActivity
    class LayerEditActivity {
        +steamInputRef: SteamInput? (静态)
    }
    class GamepadTestActivity
    class HelpActivity

    App -- MainActivity : 同一进程
    MainActivity --> LayerEditActivity : 跳转
    MainActivity --> GamepadTestActivity
    MainActivity --> HelpActivity
    LayerEditActivity ..> SteamInput : 读写运行中配置
    LayerEditActivity ..> ControllerConfig : 读写配置文件

    class UiKit {
        <<object>>
    }
    MainActivity ..> UiKit
    LayerEditActivity ..> UiKit

    class ControllerDevice {
        +name: String
        +controllerType: ControllerType
    }
    class ControllerInputMapper {
        <<object>>
    }
    ControllerInputMapper ..> ControllerDevice : 映射输入

    class AppConfig {
        +serverHost: String
        +serverPort: Int
    }
    class AppConfigStore {
        <<object>>
    }
    AppConfigStore ..> AppConfig
```

---

## 三、设计要点速览

- **层切换**：由公共层（Common）的 `SwitchLayer` 映射驱动，`OperationLayer` 无 `triggerButton` 字段；层编辑页「切入按键」读写公共层对应映射。
- **按键解析顺序**：激活层（按激活顺序）→ 公共层兜底。
- **右摇杆**：事件回调只写 `latestLookX/Y`，`SteamLike-LookLoop`（8ms）按实际 dt 积分发送，包节奏均匀不抖动。
- **TCP 协议**：8 字节定长包（键盘/鼠标移动/鼠标按钮/滚轮/释放全部/心跳），`TCP_NODELAY` 关闭 Nagle 防卡顿。
- **暂停捕获**：真正移除 1x1 焦点窗口以恢复系统返回手势；暂停后手柄事件无法到达 App，需悬浮窗按钮恢复。
- **IME 双输入防护**：仅 `CONTROL_KEY_CODES` 走按键事件通道，可打印字符只走文本通道，避免重复注入。
