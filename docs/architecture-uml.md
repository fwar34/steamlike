# SteamLike Controller — 线程模型与类图（UML）

> 本文档用 PlantUML 绘制本项目（Android 手柄映射桥接 App + Windows 注入程序）的线程模型与类图。
> 渲染方法：安装 PlantUML 插件（VS Code / IDEA / Typora）或访问 [PlantUML 在线服务器](https://www.plantuml.com/plantuml) 粘贴 `@startuml` 与 `@enduml` 之间的内容。

---

## 一、线程模型

### 1. 全局线程划分

```plantuml
@startuml
title 全局线程划分
left to right direction
skinparam componentStyle rectangle

package "① 主线程 (Main/UI Thread — Looper.getMainLooper)" {
  [GamepadInputView\n焦点输入窗口\nView 事件分发 + IME 输入] as V
  [KeyboardMouseMapper\n事件入口 + 映射执行\nonKeyEvent / onGenericMotionEvent] as M
  [SteamInput\n核心控制\n按键解析·层切换·回调分发] as SI
  [mainHandler\ncontrollerMonitor(2s 轮询手柄状态)\nUI 刷新 / 状态广播] as H
  [InputBridgeServer\nenqueue()\n事件打包入队(线程安全队列)] as SVQ
}

package "② 后台线程" {
  [startMapper 启动线程\n(匿名 Thread)\nServerSocket.bind → 初始化注入器/核心] as B1
  [BridgeServer-Accept\nacceptLoop 接受客户端] as B2
  [BridgeServer-Dispatch\ndispatchLoop 队列→所有客户端] as B3
  [BridgeServer-Client-N\nhandleClient 逐连接检测断开] as B4
  [SteamLike-LookLoop\n(守护线程 8ms 固定频率)\n右摇杆视角发送] as B5
  [SteamLike-SmartMonitor\n(守护线程 1.5s 轮询)\n智能暂停监控] as B6
}

package "③ Windows / Winlator 侧" {
  [InputBridgeClient.exe\nrecv() → SendInput() → WoW] as W
}

V --> M : dispatchKeyEvent / dispatchGenericMotionEvent
M --> SI : dispatchKeyEvent / dispatchGenericMotionEvent
SI --> M : onButtonMapped / onStickMapped 回调
M --> SVQ : sendKeyDown / sendMouseXxx
SVQ --> B3 : 入队 ConcurrentLinkedQueue
B3 --> W : 8 字节定长包 (TCP 27015)

M --> B5 : 写入 latestLookX/Y (主线程)
B5 --> SVQ : sendMouseMove(dx,dy)

B6 --> H : mainHandler.post { 暂停/恢复捕获 }
B1 --> H : mainHandler.post { createGamepadInputWindow() }
SI --> H : mainHandler.post { UI 更新 }
@enduml
```

**说明**：所有 View 事件、映射执行、注入器调用都在主线程完成；`InputBridgeServer` 的网络收发在独立后台线程；右摇杆视角用专用守护线程按 8ms 固定频率发送，保证包节奏均匀。

### 2. 按键事件流（按钮按下/释放）

```plantuml
@startuml
title 按键事件流（按钮按下/释放）
autonumber
skinparam sequenceMessageAlign center

participant "手柄硬件" as HW
participant "Android InputManager" as IM
participant "GamepadInputView\n(主线程)" as V
participant "KeyboardMouseMapper\n(主线程)" as M
participant "SteamInput\n(主线程)" as SI
participant "BridgeInputInjector\n(主线程)" as INJ
participant "InputBridgeServer\n(主线程入队)" as SV
participant "BridgeServer-Dispatch\n(后台线程)" as DISP
participant "InputBridgeClient.exe\n(Winlator)" as WIN

HW -> IM : 按键事件
IM -> V : dispatchKeyEvent()
V -> M : onKeyEvent(event)
M -> SI : dispatchKeyEvent(event)
SI -> SI : handleButtonEvent()\n解析映射（激活层 → 公共层）
alt 命中 SwitchLayer
    SI -> SI : 激活/停用对应操作层\nonLayerChanged → 悬浮窗刷新
else 命中键鼠映射
    SI --> M : onButtonMapped(button, pressed, mapping)
    M -> INJ : sendKeyDown / sendKeyUp(vkCode)
    INJ -> SV : server.sendKeyEvent(vkCode, down)
    SV -> DISP : 打包入队 messageQueue
    DISP -> WIN : 发送 8 字节协议包
    WIN -> WIN : SendInput() 注入 WoW
end
@enduml
```

### 3. 右摇杆视角流（事件 → 固定频率发送）

```plantuml
@startuml
title 右摇杆视角流（事件 → 固定频率发送）
autonumber
skinparam sequenceMessageAlign center

participant "GamepadInputView\n(主线程)" as V
participant "SteamInput\n(主线程)" as SI
participant "KeyboardMouseMapper\n(主线程)" as M
participant "SteamLike-LookLoop\n(后台线程 8ms)" as LOOK
participant "InputBridgeServer\n(主线程入队)" as SV
participant "InputBridgeClient.exe" as WIN

V -> SI : dispatchGenericMotionEvent(右摇杆)
SI --> M : onStickMapped(stick, x, y)
M -> M : 更新 latestLookX / latestLookY
note over M, LOOK : 事件线程只写状态；发送由 LookLoop 消费
loop 每 8ms 一个 tick
    LOOK -> LOOK : processLookTick(dt)\n幅值钳制 → 加速曲线 → EMA 平滑 → 位移积分
    LOOK -> SV : sendMouseMove(dx, dy)
    SV -> WIN : TCP 鼠标移动包
    WIN -> WIN : SendInput() 移动视角
end
@enduml
```

### 4. 关键线程同步手段

```plantuml
@startuml
title 关键线程同步手段
skinparam componentStyle rectangle

[messageQueue\nConcurrentLinkedQueue(ByteArray)\n（入队主线程 / 出队 Dispatch 线程）] as Q
[clients\nCopyOnWriteArrayList(ClientConnection)\n（多线程读、写时复制）] as C
[latestLookX / latestLookY\n（主线程写 / LookLoop 线程读，float 原子性）] as V1
[mainHandler\n（跨线程安全地切回主线程执行 UI 更新）] as H1
[AtomicBoolean isRunning / AtomicInteger NEXT_ID\n（服务器运行标志 / 连接自增ID）] as A1
@enduml
```

---

## 二、类图

### 1. 核心类关系

```plantuml
@startuml
title 核心类关系
skinparam classAttributeIconSize 0

class ControllerOverlayService {
  - steamInput : SteamInput?
  - mapper : KeyboardMouseMapper?
  - bridgeServer : InputBridgeServer?
  - injector : BridgeInputInjector?
  - gamepadInputView : GamepadInputView?
  - configManager : ConfigManager?
  - smartMonitorThread : Thread?
  + onCreate() : Unit
  + onStartCommand(intent, flags, startId) : Int
  + pauseCapturing() : Unit
  + resumeCapturing() : Unit
}
ControllerOverlayService *-- GamepadInputView
ControllerOverlayService --> SteamInput
ControllerOverlayService --> KeyboardMouseMapper
ControllerOverlayService --> InputBridgeServer
ControllerOverlayService --> BridgeInputInjector
ControllerOverlayService --> ConfigManager

class SteamInput {
  + profile : ControllerProfile
  + isCapturing : Boolean
  + onButtonMapped : Callback
  + onStickMapped : Callback
  + onLayerChanged : Callback
  + onActionSetChanged : Callback
  + onControllerConnected : Callback
  + dispatchKeyEvent(event) : Boolean
  + dispatchGenericMotionEvent(event) : Boolean
  + handleButtonEvent(button, pressed) : Unit
  + switchActionSet(name) : Unit
  + getActiveActionSetName() : String
}
SteamInput o-- ControllerProfile
SteamInput o-- ControllerDevice
SteamInput ..> ControllerButton

class KeyboardMouseMapper {
  - lookThread : Thread?
  - latestLookX : Float
  - latestLookY : Float
  + start() : Boolean
  + stop() : Unit
  + onKeyEvent(event) : Boolean
  + onGenericMotionEvent(event) : Boolean
  + onLayerChanged : Callback
}
KeyboardMouseMapper --> SteamInput
KeyboardMouseMapper --> InputInjector

interface InputInjector {
  + isAvailable() : Boolean
  + sendKeyDown(keyCode) : Unit
  + sendKeyUp(keyCode) : Unit
  + sendMouseMove(dx, dy) : Unit
  + sendMouseDown(button) : Unit
  + sendMouseUp(button) : Unit
  + sendMouseScroll(delta) : Unit
  + releaseAll() : Unit
}
InputInjector <|-- BridgeInputInjector
BridgeInputInjector --> InputBridgeServer

class InputBridgeServer {
  - serverSocket : ServerSocket?
  - messageQueue : ConcurrentLinkedQueue
  - clients : CopyOnWriteArrayList
  + start() : Boolean
  + stop() : Unit
  + sendKeyEvent(vkCode, isDown) : Unit
  + sendMouseMove(dx, dy) : Unit
  + sendMouseButton(button, isDown) : Unit
  + sendMouseWheel(delta) : Unit
  + sendReleaseAll() : Unit
}
InputBridgeServer *-- ClientConnection

class ClientConnection {
  - socket : Socket
  + id : Int
  + send(data) : Unit
  + close() : Unit
}

class GamepadInputView {
  + onKeyEvent : Callback
  + onGenericMotion : Callback
  + onToggleOverlay : Callback
  + onImeChar : Callback
  + onImeKey : Callback
  + dispatchKeyEvent(event) : Boolean
}

class ConfigManager {
  + saveProfile() : Unit
  + loadProfile() : ControllerProfile
}
ConfigManager --> ControllerConfig
ConfigManager --> SteamInput

class ControllerConfig {
  + {static} toJson(profile) : String
  + {static} fromJson(json) : ControllerProfile
}
@enduml
```

### 2. 数据模型（配置结构）

```plantuml
@startuml
title 数据模型（配置结构）
skinparam classAttributeIconSize 0

class ControllerProfile {
  + actionSets : List<ActionSet>
  + activeActionSetName : String
  + globalSettings : GlobalSettings
  + activeActionSet : ActionSet
  + commonLayer : OperationLayer
  + layers : List<OperationLayer>
  + allLayers : List<OperationLayer>
  + findLayer(name) : OperationLayer?
  + findActionSet(name) : ActionSet?
}
note right of ControllerProfile
  activeActionSet：getter，名称不匹配时回退到第一个
  commonLayer / layers / allLayers / findLayer
  均委托到 activeActionSet（向后兼容）
end note
ControllerProfile *-- "1..n" ActionSet
ControllerProfile *-- GlobalSettings

class ActionSet {
  + name : String
  + commonLayer : OperationLayer
  + layers : List<OperationLayer>
}
ActionSet *-- "1" OperationLayer : commonLayer
ActionSet *-- "1..10" OperationLayer : layers

class OperationLayer {
  + name : String
  + buttonMappings : MutableMap<ControllerButton, KeyMapping>
}
OperationLayer *-- KeyMapping

class KeyMapping {
  + action : MappedAction
  + subCommands : List<Int>
}
KeyMapping --> MappedAction

class GlobalSettings {
  + deadzone : Float
  + lookSensitivity : Float
  + cursorSpeed : Float
  + lookSmoothing : Float
  + lookAcceleration : Float
}

class MappedAction <<sealed>>
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
  + {static} LAYER_NAMES
}
WoWActionSets ..> ControllerProfile : 生成默认预设
@enduml
```

### 3. UI 层与辅助类

```plantuml
@startuml
title UI 层与辅助类
skinparam classAttributeIconSize 0

class App <<Application>>

class MainActivity
class LayerEditActivity {
  + {static} steamInputRef : SteamInput?
  + setupActionSetSpinner() : Unit
  + showAddActionSetDialog() : Unit
  + showCopyActionSetDialog() : Unit
  + showRenameActionSetDialog() : Unit
  + confirmDeleteActionSet() : Unit
  + switchToActionSet(actionSet) : Unit
  + copyActionSet(actionSet, newName) : ActionSet
}
note right of LayerEditActivity
  steamInputRef 为静态引用，由服务设置
  setupActionSetSpinner 切换操作集
end note
class GamepadTestActivity
class HelpActivity

App -- MainActivity : 同一进程
MainActivity --> LayerEditActivity : 跳转
MainActivity --> GamepadTestActivity
MainActivity --> HelpActivity
LayerEditActivity ..> SteamInput : 读写运行中配置
LayerEditActivity ..> ControllerConfig : 读写配置文件

class UiKit <<object>>
MainActivity ..> UiKit
LayerEditActivity ..> UiKit

class ControllerDevice {
  + name : String
  + controllerType : ControllerType
}
class ControllerInputMapper <<object>>
ControllerInputMapper ..> ControllerDevice : 映射输入

class AppConfig {
  + serverHost : String
  + serverPort : Int
}
class AppConfigStore <<object>>
AppConfigStore ..> AppConfig
@enduml
```

---

## 三、设计要点速览

- **配置分层**：`ControllerProfile` = 多个操作集（[`ActionSet`] 各自含公共层 + 操作层）+ 当前操作集名 + 全局设置；切换操作集时其下所有操作层整体切换。向后兼容访问器（`commonLayer`/`layers`/`allLayers`/`findLayer`）委托到当前操作集。
- **操作集管理**：层编辑页顶部 Spinner 切换操作集，支持添加/拷贝/改名/删除；拷贝深拷贝各层映射表（新 `LinkedHashMap`），避免操作集间共享可变 Map；切换后悬浮窗展开面板刷新「操作集: X」。
- **层切换**：由公共层（Common）的 `SwitchLayer` 映射驱动，`OperationLayer` 无 `triggerButton` 字段；层编辑页「切入按键」读写公共层对应映射。
- **按键解析顺序**：激活层（按激活顺序）→ 公共层兜底。
- **右摇杆**：事件回调只写 `latestLookX/Y`，`SteamLike-LookLoop`（8ms）按实际 dt 积分发送，包节奏均匀不抖动。
- **TCP 协议**：8 字节定长包（键盘/鼠标移动/鼠标按钮/滚轮/释放全部/心跳），`TCP_NODELAY` 关闭 Nagle 防卡顿。
- **暂停捕获**：真正移除 1x1 焦点窗口以恢复系统返回手势；暂停后手柄事件无法到达 App，需悬浮窗按钮恢复。
- **IME 双输入防护**：仅 `CONTROL_KEY_CODES` 走按键事件通道，可打印字符只走文本通道，避免重复注入。
- **配置版本**：`steamlike_config.json` 为 version=3（操作集格式），加载 version=2 旧格式时自动迁移为「默认」操作集。
