# Winlator 手柄事件与 WoW 交互问题

> **Workspace:** l:\steamlike

---

**User**

我切换捕获功能绑定到一个手柄按键上了，按下后切换到了暂停捕获，这时候手柄事件传递不到wow了，但是在winlator的wow边缘右滑还是返回不到桌面

**Assistant**

让我先探索代码库，理解"切换捕获"功能的实现。



让我查找所有引用 `gamepadInputView` 和相关状态的地方，确认修改影响范围。



我已理解整个架构。先确认一下当前"暂停捕获"的行为逻辑。

**根因分析：**

1. [ToggleCapture](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/core/MappingTypes.kt#L131-136) 绑到手柄按键 → 按下后调用 [toggleCaptureState()](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L849-857) → [pauseCapturing()](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L823-830)。
2. [pauseCapturing()](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L823-830) 只把 `isCapturing` 标志置 false，**并没有移除可获焦点的 GamepadInputView 窗口**（注释明确写着"永远不移除、永远可获焦点"）。
3. 而[代码注释](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L778-781)和智能暂停的说明都指出：**有焦点的 TYPE_APPLICATION_OVERLAY 窗口会让 Android 13+ 预测式返回手势失效（右滑返回被吃掉）**。智能暂停的解决方案就是"移除窗口恢复手势"。

所以：手柄事件确实不再映射到 WoW（符合预期），但因为焦点窗口还在，Winlator 的边缘右滑返回桌面依然被拦截。

另外我还发现一个现状：暂停后 `SteamInput.dispatchKeyEvent` 直接 `if (!isCapturing) return false`，**手柄根本无法再通过同一个 ToggleCapture 键恢复捕获**（[SteamInput.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/core/SteamInput.kt#L341-342)）。也就是说"保留窗口以便手柄恢复"的设计在当前实现里其实并不生效，反而是白背了"手势被吃"的代价。

**修复方案：**暂停捕获时真正移除 GamepadInputView 焦点窗口（与智能暂停一致），恢复时重建。恢复可通过悬浮窗"恢复捕获"按钮 / MainActivity 开关完成。



现在实施修复。修改 [ControllerOverlayService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt) 中的暂停/恢复捕获逻辑。



再修正 [resumeOverlay()](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L741-756)：设置界面关闭时，若捕获原本处于暂停状态，不应重建焦点窗口。



现在确认一下悬浮窗"暂停/恢复捕获"按钮与 MainActivity 开关的同步逻辑，避免修复后出现"按钮文字与实际状态不一致、需要点两次才能恢复"的问题。



修复核心问题已改完。现在处理配套的恢复路径：由于暂停后游戏手柄无法再触发恢复（窗口已移除），悬浮窗的"暂停/恢复捕获"按钮成为主要恢复入口。但当前该按钮文字绑定的是 `captureEnabled` 而非实际捕获状态，用户用 ToggleCapture 暂停后按钮仍显示"暂停捕获"，会导致要点两次才恢复。需要一并修正按钮逻辑。



现在更新按钮文字绑定与点击回调，使它们基于实际捕获状态 `isCapturing`。



让我检查修改后的完整代码块，确认逻辑与编译无误。



让我检查编辑后的文件是否有编译诊断错误。



诊断无错误。尝试编译验证（先用离线模式避免代理问题）。



编译通过（exit 0，离线模式）。修复完成。



## 问题根因

你暂停捕获后，手柄事件确实不再映射到 WoW，但**可获焦点的 GamepadInputView 窗口并没有被移除**——它只是把 `isCapturing` 置为 false。而[代码注释](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L778-781)和智能暂停机制都明确指出：**有焦点的 TYPE_APPLICATION_OVERLAY 窗口会让 Android 13+ 的预测式返回手势失效**（右滑返回被系统拦截）。所以 Winlator 里的边缘右滑返回桌面依然无效。

另外我还发现：暂停后 [SteamInput.dispatchKeyEvent](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/core/SteamInput.kt#L341-342) 直接 `if (!isCapturing) return false`，**手柄其实根本无法再用同一个键恢复捕获**——"保留窗口以便手柄恢复"的设计在当前实现里并不生效，只是白背了"手势被吃"的代价。

## 修改内容（均在 [ControllerOverlayService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt)）

1. **`pauseCapturing()`**：暂停时真正移除 GamepadInputView 焦点窗口（与智能暂停的做法一致），恢复系统边缘右滑返回手势。
2. **`resumeCapturing()`**：恢复时重建焦点窗口。
3. **`resumeOverlay()`**：设置界面关闭重建时，若捕获原本已暂停则保持暂停，不重建窗口。
4. **悬浮窗按钮**：文字改为跟随实际捕获状态 `isCapturing`（暂停时正确显示"恢复捕获"），点击通过新的 `toggleCaptureFromButton()` 切换并同步 `captureEnabled`/MainActivity 开关，避免原来"要点两次才能恢复"的问题。
5. **`toggleCaptureState()`**（手柄 ToggleCapture 路径）：增加状态广播，让 MainActivity 的开关显示与实际一致。

## 行为变化（请注意）

- 暂停捕获后**游戏手柄无法再通过 ToggleCapture 键恢复**（窗口已移除，这是恢复手势的必然代价）。恢复请用悬浮窗胶囊 → 展开 → **"恢复捕获"按钮**，或 MainActivity 的手柄捕获开关，一次点击即可。
- 暂停后胶囊仍显示"⏸"标记，方便识别当前状态。

已通过 `./gradlew :app:compileDebugKotlin --offline` 编译验证（exit 0）。可以重新安装 APK 测试：绑定 ToggleCapture 到按键 → 按下暂停 → 此时 Winlator 里边缘右滑应能正常返回桌面。

---

**User**

推送手机

**Assistant**

我来构建 APK 并推送到手机。先检查设备连接并开始构建。



---

**User**

直接推送

**Assistant**

用户要求直接推送安装到手机。先构建 APK 并检查 adb 设备连接。



adb 不在 PATH 中。查找 Android SDK 中的 adb。



找到 adb。检查设备连接。



设备已连接（813a7baf）。开始构建 Debug APK。





构建成功。安装 APK 到手机。



安装成功。启动应用让用户直接测试。



已完成推送安装并启动应用。

- 构建成功（同时重编了 Windows exe 并同步进 assets）
- 安装到设备 `813a7baf`：`Success`
- 已启动 MainActivity

可以测试：绑定 ToggleCapture 到手柄按键 → 按下暂停捕获 → 在 Winlator 里边缘右滑应能正常返回桌面；恢复用悬浮窗胶囊里的"恢复捕获"按钮或 MainActivity 开关。

---

**User**

切换到暂停捕获后可以右滑退出了，但是再次按键出发切换捕获没法恢复捕获

**Assistant**

用户期望：暂停捕获（窗口已移除、手势恢复）后，**按同一手柄键能恢复捕获**。但窗口移除后手柄事件不再送达应用（无焦点窗口接收）。要做到这点，标准方案是给应用加一个**无障碍服务（AccessibilityService，FLAG_REQUEST_FILTER_KEY_EVENTS）**，它能在无焦点时全局收到手柄按键事件，从而在暂停状态下仅处理切换类动作（如 ToggleCapture）实现恢复。

先探查相关代码确认实现方案。



现在查看 `MappedAction` 定义、`ControllerInputMapper` 和字符串资源，确认切换动作的完整类型。





没有 strings.xml（字符串均为硬编码）。查看服务类的 onCreate/onDestroy 和 companion 结构，以便接入无障碍转发入口。





设计方案确认：添加**无障碍服务（FLAG_REQUEST_FILTER_KEY_EVENTS）**，在暂停捕获（焦点窗口已移除）时全局接收手柄按键，仅触发切换类映射（ToggleCapture 等）实现恢复；捕获进行中直接穿透，不干扰正常输入。开始实施。

先查看服务文件顶部 import，确认需要新增的引用。



继续实施无障碍服务方案。先读取当前关键文件状态。



继续读取关键代码段。



现在读取 KeyboardMouseMapper 的关键方法，确认映射处理逻辑。



---

**User**

继续

**Assistant**

继续实施无障碍服务方案。先读取剩余关键代码段。



现在查看 res 目录结构，确认是否有 strings.xml。



先确认 pauseOverlay/resumeOverlay 逻辑，避免与无障碍方案冲突。



现在开始实施无障碍服务方案。



创建字符串资源和无障碍服务配置 XML。



创建无障碍服务类。



更新 todo 并在 AndroidManifest 添加无障碍服务声明。



现在给 SteamInput 添加暂停态按键处理方法。



现在修改 ControllerOverlayService 注册/清除无障碍转发回调。



在 onDestroy 中清除无障碍回调。



现在编译验证。先确认 minSdk 兼容性。



minSdk 24，兼容。开始编译。



`AccessibilityServiceInfo` 是 Java 类没有 `copy()`。改用 setter 直接修改 capabilities。



查看当前文件的报错位置。



`AccessibilityServiceInfo.capabilities` 是只读的。manifest 已声明 `flagRequestFilterKeyEvents`，无需动态设置，直接移除这段冗余代码。



移除未使用的 import 并重新编译。





编译成功。推送安装到手机。



设备未连接。检查 adb 状态。



设备列表为空，可能连接断开。重启 adb 服务重试。



手机当前未连接 adb（设备列表为空）。需要确认连接方式才能继续推送。



请提供手机的**无线调试地址**（格式 `IP:端口`）。获取方法：

1. 手机进入 **设置 → 开发者选项 → 无线调试**
2. 查看 **IP 地址和端口**（形如 `192.168.1.100:5555`，注意是"IP 地址和端口"这一项，不是"配对码"那一项）

把地址发给我，我用 `adb connect` 连上后推送。

---

**User**

26.26.26.1:44297

**Assistant**

连接无线调试设备。



连接超时（网络不可达）。检测一下网络连通性并重试。



`26.26.26.1` 无法 ping 通。检查电脑当前网段，确认是否与手机在同一网络。



手机与电脑**不在同一网络**，无法连通：

- **电脑**（Wi-Fi）网段：`192.168.125.181`
- **手机**给的地址：`26.26.26.1`（不同网段，ping 超时）

`26.26.26.1` 看起来不是手机在电脑所连 Wi-Fi 下的地址。请按以下步骤操作：

1. **手机连接到与电脑相同的 Wi-Fi**（电脑当前在 `192.168.125.x` 这个网络）
2. 进入 **设置 → 开发者选项 → 无线调试**，开启后点进"使用配对码配对设备"那一项，确认手机显示的是**同一 Wi-Fi 下的 IP**（应为 `192.168.125.x` 开头）
3. 把新的 **IP:端口** 发我（注意用"IP 地址和端口"一栏，不是配对码端口）

或者更省事：直接用 **USB 数据线**连接手机（开启 USB 调试），我检测到后立即推送。

---

**User**

192.168.125.122:

**Assistant**

收到 `192.168.125.122`，和电脑同网段。但端口缺失，先 ping 确认连通并尝试默认端口。



---

**User**

192.168.125.122:44297

**Assistant**

手机地址已在同一网段。连接并推送。





仍是超时。先确认手机 IP 是否可达。



`192.168.125.122` 返回"无法访问目标主机"——手机当前在该 IP 上**不可达**。可能原因：手机 Wi-Fi 断开/休眠、IP 已重新分配、或无线调试端口已失效。

建议改用更稳定的方式：

**方式一（推荐）：USB 有线连接**
1. 用数据线把手机连到电脑
2. 手机弹出"允许 USB 调试"授权时点**允许**
3. 告诉我，我立即检测并推送

**方式二：重新获取无线地址**
1. 确认手机 Wi-Fi 连的是电脑所在的同一路由器（`192.168.125.x` 网段）
2. **保持屏幕常亮**，停留在 **设置 → 开发者选项 → 无线调试** 页面
3. 把最新的 **IP:端口** 发我（端口每次重开都可能变化）

APK 已构建好，设备一连上就能装。

---

**User**

usb推送把

**Assistant**

检测 USB 设备。





设备已通过 USB 连接。推送安装。





安装成功。更新任务状态。





已安装到手机。本次改动解决了"暂停捕获后无法用同一手柄键恢复"的问题：

**改动内容**
- 新增 [GamepadAccessibilityService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/GamepadAccessibilityService.kt) — 无障碍服务，暂停捕获时全局接收手柄按键
- 新增 [accessibility_service_config.xml](file:///l:/steamlike/app/src/main/res/xml/accessibility_service_config.xml) 及 strings.xml
- [AndroidManifest.xml](file:///l:/steamlike/app/src/main/AndroidManifest.xml) 声明无障碍服务
- [SteamInput.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/core/SteamInput.kt#L357-L398) 新增 `dispatchKeyEventWhilePaused`，暂停态只处理切换类动作（ToggleCapture 等），不注入普通键鼠
- [ControllerOverlayService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L824-L846) 暂停时注册转发回调、恢复时清除

**原理**：暂停捕获移除焦点窗口后，手柄事件经无障碍服务回到应用 → 识别出"切换捕获"映射 → 触发 `resumeCapturing()` 重建焦点窗口。

**需要你手动操作一步**（无障碍服务系统必须手动开启）：
1. 手机 **设置 → 无障碍 → 已下载的服务 → "SteamLike手柄按键恢复"**，打开开关
2. Android 13+ 会额外弹窗询问"是否允许 SteamLike 读取按键"→ 点**允许**

开启后测试流程：暂停捕获 → 右滑回桌面 → 再按绑定"切换捕获"的手柄键 → 应恢复捕获。

如果未开启无障碍服务，暂停时会弹 Toast 提示"恢复请用悬浮窗按钮或主界面开关"，功能不受影响。

---

**User**

不行，暂停捕获后再切换捕获恢复不了

**Assistant**

先检查手机上无障碍服务是否真的开启、以及按键事件是否到达了无障碍服务。





关键发现：`enabled_accessibility_services` 返回 **null**，`Enabled services:{}` 为空 —— **无障碍服务根本没有启用**，所以按键无法经它转发。先用 adb 直接启用服务并验证。



adb 写入被小米系统拒绝（需要 WRITE_SECURE_SETTINGS 权限）。无障碍服务**必须手动开启**。先确认服务名称和配置，给你准确指引。





代码配置无误（服务名"SteamLike手柄按键恢复"）。现在的问题是服务**未开启**。我帮你直接打开手机的无障碍设置界面。







手机已打开**无障碍设置**界面。请在手机上操作：

1. 在列表里找到 **"SteamLike手柄按键恢复"**（可能在"已下载的服务"或"服务"分组下）
2. 打开它的开关
3. 若弹出授权提示（如"是否允许 SteamLike 通过按键过滤读取按键"）→ 点**允许**
4. 开启后跟我说一声，我立刻通过日志验证按键是否真的到达

另外提醒：如果服务详情页里有单独的 **"按键过滤/按键事件过滤"** 开关（Android 13+ 常见），也一并打开。

开启成功后测试流程：暂停捕获 → 右滑回桌面 → 按"切换捕获"手柄键，看能否恢复。

---

**User**

开启了

**Assistant**

验证服务是否已启用并连接。





服务已绑定（`Bound services` 非空），但注意 **`capabilities=0`** —— 说明**按键过滤能力未被授予**（Android 13+ 需要用户在服务详情里单独开启"按键过滤"）。先确认系统版本并查看服务详情。





找到根因了。系统是 **Android 16（SDK 36）**，而 **Android 13+ 之后，`FLAG_REQUEST_FILTER_KEY_EVENTS` 必须在 `onServiceConnected` 里**运行时动态设置**，XML 静态声明不再生效**（所以 `capabilities=0`）。我的服务代码只设置了 `isConnected=true`，没动态请求按键过滤。先确认当前注册逻辑，然后修复。



注册逻辑没问题。修复服务：动态请求按键过滤能力，并加按键日志便于验证。



重新构建并推送。





构建成功。安装到手机。





安装成功。检查服务更新后是否重新绑定、capabilities 是否变化。







`capabilities` 仍为 0。先看服务是否在安装更新后重新触发了 `onServiceConnected`。





logcat 里**没有** `SteamLikeA11y` 日志——说明安装更新后服务**还没用新代码重新连接**（onServiceConnected 未重跑，所以动态请求按键过滤的代码没执行）。

请在手机上**关闭再重新打开**这个无障碍服务：

1. **设置 → 无障碍 → "SteamLike手柄按键恢复"** → 先**关掉**开关 → 再**打开**
2. 打开后进入该服务的**详情页**，找是否有 **"按键过滤"**（或"按键检测"）开关，有就**开启**（Android 13+ 通常要这一步）
3. 完成后告诉我，我验证日志

我先清空日志缓冲，方便等下看新输出。



日志已清空。请按上面步骤操作（关闭→重新打开服务，并在详情页开启"按键过滤"），完成后告诉我。

---

**User**

好了

**Assistant**

验证服务重新连接后的状态和日志。





日志仍为空，capabilities 仍为 0。检查是否有服务崩溃或日志 tag 问题。







服务已重新连接（`13:46:40.569 I SteamLikeA11y: Accessibility service connected`）。但注意**没有**打印 "Requested FLAG_REQUEST_FILTER_KEY_EVENTS" —— 说明 XML 里的 `flagRequestFilterKeyEvents` 已生效，flags 本就含该位。现在关键是实测按键是否真能到达服务。

请在手机上做一次完整测试：
1. 打开 **SteamLike** 应用，确认悬浮窗服务已启动
2. 进入 **WoW（Winlator）**
3. 按"切换捕获"手柄键 → 进入**暂停捕获**
4. **右滑退出**到桌面
5. **再按一次**"切换捕获"手柄键
6. 告诉我"恢复了"还是"没恢复"

测试完告诉我结果，我立即抓取日志分析。

---

**User**

没恢复，你现重新编译推送次app把

**Assistant**

先抓日志分析原因，避免盲目重编。







日志揭示了关键信息：

```
13:48:23  GamepadInputView created
13:48:24  Capturing paused          ← 按切换键暂停
13:48:29  Capturing resumed         ← 再按切换键，通过无障碍恢复了！
13:48:32  Capturing paused          ← 又暂停
```

**恢复其实成功过一次**（13:48:29），但随后再次暂停后就恢复不了了。说明无障碍转发本身能工作，但存在状态问题。查看 `dispatchKeyEventWhilePaused` 和切换逻辑的完整实现。





`dispatchKeyEventWhilePaused` 逻辑本身正常。问题可能在 `getEffectiveMapping`（暂停时从哪个层查映射）。查看其实现。







`getEffectiveMapping` 逻辑无异常。第一次恢复成功、第二次失败，需要在暂停分发链路加详细日志定位断点。修改 `dispatchKeyEventWhilePaused` 打全链路日志。







确认 `ControllerOverlayService` 中 `onButtonMapped` 与 Toggle 回调的连接。





`ControllerOverlayService` 里**没有** `onButtonMapped` 设置。检查 `SteamInput` 里的回调定义和映射器的调用链。







链路检查完毕（`onButtonMapped` → `handleMapping` → `onToggleCapture` → `toggleCaptureState` 是通的）。但日志里**从头到尾没有出现 "Paused key"**——说明 `onKeyEvent` 根本没收到手柄事件，13:48:29 的"恢复"其实是手动触发的。

问题指向**按键过滤未真正生效**（API 33+ 需要用户额外授权）。为确认 `onKeyEvent` 是否被调用，在它最开头加无条件日志（捕获中也打），这样能区分"事件没到服务"还是"到了但转发失败"。



重新编译推送。







安装完成、日志已清空。请按以下顺序测试一遍：

1. 打开 **SteamLike**，启动悬浮窗服务
2. **捕获中**，随手按一下手柄任意键（用于确认按键是否到达无障碍服务）
3. 按"**切换捕获**"键 → 暂停捕获
4. **右滑退出**到桌面
5. **再按一次**"切换捕获"键

完成后告诉我结果，我立即抓日志看 `A11yKey` 标记。

---

**User**

好了

**Assistant**

抓日志分析。







关键结论：**`onKeyEvent` 从未被调用**（没有 `A11yKey` 日志）——按键过滤没有生效。另外注意到一个线索：13:51:40 触发的是 **Smart pause**（智能暂停），说明你测试时进入了 Winlator 之外的前台。

现在用 adb 直接注入一个手柄按键，验证 `onKeyEvent` 是否收到任何按键（这会绕过手柄硬件，直接测系统按键过滤链路）。





我来打开无障碍服务的详情页，让你开启"按键过滤"授权。



adb 无权直接打开服务详情页。我改用打开无障碍设置列表页，引导你手动进入详情页开启"按键过滤"。



手机上应已打开**无障碍设置**页面。请按以下步骤操作：

1. 在列表中找到 **"SteamLike手柄按键恢复"**（可能叫"SteamLike 手柄按键恢复"），点击**进入它的详情页**（不要只点开关）。
2. 在详情页里找到 **"按键过滤"**（英文叫 *Watch keyboard keys*，小米可能显示为"按键检测"或"键盘按键"），把这个开关**打开**。
3. 如果详情页没有这个选项，可能需要先展开"已下载的应用"或查看**已安装的服务**列表。

这是 Android 13+ 的独立授权，只开服务总开关不够，必须单独授予"按键过滤"能力，否则按键根本不会送到我们的服务。

开启后告诉我，我会用 `dumpsys accessibility` 验证是否已生效。

---

**User**

编译推送手机

**Assistant**

构建成功。现在安装到手机。



安装成功。检查一下无障碍服务的按键过滤授权状态。



APK 已编译并安装成功。但我刚检查了无障碍服务状态：

```
Enabled services: {{com.steamlike.controller/...GamepadAccessibilityService}}
Bound services: { ..., capabilities=0, ... }
```

**问题确认**：服务已启用、已绑定，但 `capabilities=0` —— **"按键过滤"授权仍未开启**。这导致按键根本不会送到我们的服务，`onKeyEvent` 不会触发，所以"暂停后用同一手柄键恢复捕获"依然无效。

需要你在手机上手动开启这个独立授权（Android 13+ 特有，服务总开关开了也不算）：

1. **设置 → 无障碍 → 已下载的应用 → SteamLike手柄按键恢复**（进入**详情页**，不是只点总开关）
2. 找到 **"按键过滤"**（小米可能显示为"按键检测"或"Watch keyboard keys"）
3. 打开它

我先在手机上打开无障碍设置列表页。



手机上已打开无障碍设置列表页。请按上面的步骤进入 **SteamLike手柄按键恢复** 的详情页，找到 **"按键过滤"** 开关并打开。

开启后告诉我，我再用 `dumpsys accessibility` 验证是否生效（`capabilities` 应变为非 0）。

---

**User**

前面的不用试了，直接编译推送把

**Assistant**

编译并推送完成，`Success` 已安装到手机。

之前那版代码（无障碍服务转发）已经包含在这次推送里了。你可以直接打开应用测试了。

---

**User**

帮我检查无障碍方案的相关逻辑。以前成功过

**Assistant**

我来检查无障碍方案的所有相关逻辑。



链路检查完毕。现在看 git 历史，确认"以前成功过"的版本。



---

**User**

说的以前不是当前工程，是我其他的工程，你直接检查逻辑就行

**Assistant**

明白，直接检查当前工程逻辑。我重点再看智能暂停监控和事件分发入口。



逻辑检查完毕。链路是**完整且正确**的，逐环节确认如下：

**暂停 → 恢复的完整链路**

1. **捕获中按"切换捕获"**：`GamepadInputView.dispatchKeyEvent` → [SteamInput.handleButtonEvent](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/core/SteamInput.kt#L553-L592) → `onButtonMapped` → [KeyboardMouseMapper.handleMapping](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/mapping/KeyboardMouseMapper.kt#L289-L316) → `onToggleCapture` → [toggleCaptureState](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L864-L874) → `pauseCapturing()` ✓

2. **`pauseCapturing()`**（[ControllerOverlayService.kt#L824-L841](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L824-L841)）：移除焦点窗口、`isCapturing=false`、注册 `onPausedKeyEvent` 转发 ✓

3. **暂停后按同一键**：无障碍 `onKeyEvent` → [dispatchKeyEventWhilePaused](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/core/SteamInput.kt#L371-L405) → 校验来源/设备/映射，识别 Toggle 动作 → `onButtonMapped` → `handleMapping` → `onToggleCapture` → `toggleCaptureState` → `resumeCapturing()` ✓

4. **`resumeCapturing()`**（[ControllerOverlayService.kt#L843-L856](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L843-L856)）：清空 `onPausedKeyEvent`、重建窗口 ✓

`manualPaused` 的配合也正确：手柄键手动暂停时置 `true`，防止智能暂停监控自动恢复，再按同一键恢复时置 `false`。**代码逻辑没有 bug。**

**真正的卡点在系统授权层，不是逻辑**：

- `dumpsys accessibility` 显示 `capabilities=0`，`onKeyEvent` 从未被触发（此前 logcat 无 `A11yKey` 日志）
- Android 13+ 把 `FLAG_REQUEST_FILTER_KEY_EVENTS` 视为**敏感能力**，即使服务已启用、XML 已声明、代码也动态请求了该 flag（[GamepadAccessibilityService.kt#L50-L58](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/GamepadAccessibilityService.kt#L50-L58)），系统仍强制要求用户在**服务详情页单独开启"按键过滤"**，否则 capabilities 恒为 0、按键永不送达

**建议的代码改进**（可选，能显著提升可诊断性）：在 `GamepadAccessibilityService` 中记录 `serviceInfo.capabilities` 并暴露"按键过滤是否已授权"的字段，`pauseCapturing()` 里检测到服务已连接但未授权时，明确 Toast 提示"请在无障碍服务详情页开启按键过滤"。

需要我加上这个检测与引导逻辑吗？还是你想先在手机上手动授权验证？

---

**User**

不是这个逻辑，我让你检查无障碍方案的逻辑

**User**

继续

**Assistant**

明白了，聚焦检查 `GamepadAccessibilityService` 服务自身的实现逻辑。逐点核对后，发现一个**真实的逻辑问题**：

**问题：`onServiceConnected` 里的动态按键过滤请求被 XML 声明"短路"了**

[GamepadAccessibilityService.kt#L50-L58](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/GamepadAccessibilityService.kt#L50-L58)：

```kotlin
val want = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
if (info.flags != want) {        // ← 这里恒为 false！
    info.flags = want
    serviceInfo = info            // ← 永远不会执行
    Log.i(TAG, "Requested FLAG_REQUEST_FILTER_KEY_EVENTS")
}
```

因为 [accessibility_service_config.xml](file:///l:/steamlike/app/src/main/res/xml/accessibility_service_config.xml#L5) 里已经声明了 `flagRequestFilterKeyEvents`，`serviceInfo.flags` 读出来已包含该位，`info.flags != want` 恒为 false —— **这段"运行时动态请求"代码形同虚设**，永远不会重新赋值 `serviceInfo`，也就永远不会触发系统的按键过滤授权流程。

**其他逻辑核对（均正确）**：

1. **服务声明**：[AndroidManifest.xml#L68-L79](file:///l:/steamlike/app/src/main/AndroidManifest.xml#L68-L79) 的 `BIND_ACCESSIBILITY_SERVICE` 权限 + intent-filter + meta-data 指向 XML，正确。
2. **XML 配置**：`flagRequestFilterKeyEvents` + `typeAllMask`，正确。
3. **`onKeyEvent` 返回值语义**：捕获中 handler 为 null 返回 `false` 穿透、暂停时返回 handler 结果，正确。
4. **缺少能力自检**：服务**没有检查 `serviceInfo.capabilities & CAPABILITY_CAN_FILTER_KEY_EVENTS`**。`dumpsys` 已证实 `capabilities=0`，但代码无法感知，`pauseCapturing()` 里只判断了 `isConnected`（服务已连接为 true），所以暂停时不会提示用户"按键过滤未授权"。

**"以前成功过"的工程与此的典型差异**：通常那种工程在 XML **不**声明 `flagRequestFilterKeyEvents`，只在 `onServiceConnected` 里运行时设置 flags → 触发系统弹窗"允许过滤按键"，用户点允许即获得能力；而当前实现走的是"XML 预声明 + 详情页手动开关"路径，两者互相抵消导致动态请求永远不触发。

**建议修复**（改 [GamepadAccessibilityService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/GamepadAccessibilityService.kt)）：

1. 删除 XML 中的 `flagRequestFilterKeyEvents`，改为纯运行时动态请求，触发系统授权弹窗；
2. 增加 capabilities 自检，暂停时若未授权则明确提示。

要我按这个方案改吗？

---

**User**

需要

**Assistant**

按方案修改。先改 XML，再改服务自身逻辑，最后在暂停时增加未授权提示。



修改完成。编译验证。



常量名拼错了，正确的是 `CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS`。



编译成功。安装到手机。



修改完成并已推送安装。改动汇总：

**1. [accessibility_service_config.xml](file:///l:/steamlike/app/src/main/res/xml/accessibility_service_config.xml#L5)** — 移除 `flagRequestFilterKeyEvents`，避免预声明路径短路运行时动态请求。

**2. [GamepadAccessibilityService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/GamepadAccessibilityService.kt)** — 
- `onServiceConnected` 改为**无条件**设置 flags 并重新赋值 `serviceInfo`，触发系统按键过滤授权流程（弹窗或详情页开关）
- 新增 `hasKeyFiltering` 字段，读取 `CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS` 自检是否已授权

**3. [ControllerOverlayService.kt#L836-L839](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt#L836-L839)** — 暂停捕获时若服务已连接但按键过滤未授权，会明确 Toast 提示去详情页开启"按键过滤"。

**接下来需要你在手机上操作验证**：

1. 打开 **设置 → 无障碍 → SteamLike手柄按键恢复**（可能需要先关再开一次服务，让 `onServiceConnected` 重新执行）
2. 留意是否弹出 **"允许过滤按键？"** 授权对话框，点**允许**
3. 如果没弹窗，就在服务详情页找 **"按键过滤"** 开关打开
4. 之后测试：暂停捕获 → 右滑退出 → 按"切换捕获"手柄键看能否恢复

我可以先拉一下 logcat 确认新的 "Requested FLAG_REQUEST_FILTER_KEY_EVENTS" 和 "Key filtering capability granted" 日志，需要我现在清空日志并等你在手机上操作后抓取吗？

---

**User**

需要

**Assistant**

先清空 logcat。





logcat 已清空。请按下面步骤操作（重点是让服务重新连接，触发新的 `onServiceConnected` 逻辑）：

1. **设置 → 无障碍 → 已下载的应用 → SteamLike手柄按键恢复**
2. 先把服务**关掉，再重新打开**（让服务重新绑定，触发动态按键过滤请求）
3. 留意是否弹出 **"允许过滤按键？"** 的授权对话框 → 点**允许**
4. 如果没弹窗，就在该服务详情页里找 **"按键过滤"** 开关，手动打开

操作完成后告诉我，我抓取 logcat 验证 `Requested FLAG_REQUEST_FILTER_KEY_EVENTS` 和 `Key filtering capability granted: true` 是否出现。

---

**User**

好了

**Assistant**

动态请求已执行，但 `caps=0`，系统仍未授予按键过滤能力。检查一下当前授权状态。





确认了：即使服务重连、动态请求已执行，`capabilities=0` 依然不变。这说明在这台小米 Android 16 设备上，运行时动态请求无法自动获得按键过滤能力（没有触发系统授权弹窗）。

关键要看服务详情页里是否真的有"按键过滤"这个开关。



用户确认详情页**没有"按键过滤"选项**——这是小米 HyperOS 阉割了 Android 13+ 的按键过滤授权开关，导致 `capabilities=0`、`onKeyEvent` 永不触发。无障碍按键过滤方案在这台设备上走不通了。

我先看一下当前窗口的实现，评估替代方案。



关键发现：[GamepadInputView.kt#L42-L53](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/injection/GamepadInputView.kt#L42-L53) 的注释显示，这个窗口**已经是 1x1 像素**——设计目的正是"避免影响系统返回手势"。也就是说，**1x1 可聚焦窗口本身不影响右滑返回**。

那问题就清楚了：**暂停时其实不需要移除窗口**，保留 1x1 窗口，手柄按键就能继续到达应用，也就不需要无障碍服务了。让我确认 git 历史，看看 1x1 窗口是什么时候引入的，以及"移除窗口"的代码是否已经过时。



看到关键信息了。查看 `55d37c0` 提交对 GamepadInputView 的具体改动，确认 1x1 窗口何时引入。



查看 1x1 窗口何时引入，判断"保留窗口"是否可行。



确认关键事实：**1x1 焦点窗口早在 8月18日（[7430d2a](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/injection/GamepadInputView.kt)）就从全屏改成了 1x1 像素**，设计初衷正是"**不覆盖屏幕、避免影响系统返回手势**"。

也就是说：**暂停时根本不需要移除窗口**。保留 1x1 焦点窗口既能接收手柄按键（让"切换捕获"键恢复捕获），又不影响右滑返回手势。这完全绕开了不可用的无障碍按键过滤。

我来实施这个方案：暂停时**保留窗口**，`dispatchKeyEvent` 在暂停态处理切换动作。



现在更新键盘弹出场景为移除窗口。



继续完成 1x1 窗口方案的收尾。先检查 `ControllerOverlayService.kt` 的当前状态。



当前 [ControllerOverlayService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt) 的核心逻辑已基本完成。我注意到两个小问题需要收尾：`pauseCapturing` 上方残留了旧的 KDoc 注释（描述旧行为），以及 `resumeCapturing` 的日志文案不准确。先检查 `SteamInput.kt` 的暂停态逻辑确认无误。



`SteamInput.kt` 逻辑确认正确。现在清理 [ControllerOverlayService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt) 中残留的旧 KDoc 注释（描述的是旧行为，与新实现矛盾），并修正 `resumeCapturing` 的日志。



现在检查其他调用点，特别是 `LayerEditActivity` 通过 Intent 暂停/恢复 overlay 的逻辑，确认暂停时窗口保留是否会阻塞其返回手势。



`pauseOverlay` 会显式移除窗口，LayerEditActivity 场景已安全。现在确认 [GamepadInputView.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/injection/GamepadInputView.kt) 的窗口尺寸确实是 1x1。



确认 1x1 窗口方案成立。检查 `GamepadInputView.isCapturing` 属性是否被引用同步（避免残留无用逻辑）。



`GamepadInputView.isCapturing` 是无引用属性（预存在），不影响。代码收尾完成，开始编译。



编译成功。推送 APK 到手机。



没有检测到设备。重启 adb 服务再试。



APK 编译完成，但手机未通过 USB 连接（`adb devices` 为空）。请把手机用 USB 线连接到电脑，并确保已开启 USB 调试（如弹窗请点允许）。连接好告诉我，我立即推送安装。

---

**User**

推送手机

**Assistant**

设备已连接，推送安装。



安装成功。本次改动采用 **1x1 焦点窗口保留方案**（不再依赖无障碍服务）：

- 暂停捕获时**保留** 1x1 透明焦点窗口（在角落，不遮挡屏幕边缘，不影响右滑返回桌面），手柄按键仍能到达应用
- `SteamInput` 在暂停态只处理"切换捕获/切换悬浮窗/切换键盘"这类切换动作
- 键盘弹出场景仍会强制移除窗口，避免键盘输入被窗口拦截

请验证：
1. 进入 Winlator 游戏，按"切换捕获"手柄键暂停捕获 → 边缘右滑应能返回桌面
2. 返回桌面后，再按"切换捕获"手柄键 → 应能恢复捕获（手柄事件重新生效）

---

**User**

现在切换捕获可以暂停和恢复了，但是我切换键盘功能把键盘显示后输入没法到winlator的wow中

**Assistant**

让我先查看键盘切换逻辑和输入焦点处理，分析输入无法到达 WoW 的原因。



让我检查悬浮窗 overlayView 的焦点属性——如果它可获焦点，移除 GamepadInputView 后焦点可能被悬浮窗抢走而不是回到 Winlator。



悬浮窗是 `FLAG_NOT_FOCUSABLE`，不会抢焦点。现在确认这个键盘弹出顺序是否在本次改动中发生了变化——先看 git 变更。





分析清楚了。问题根因在于键盘弹出顺序：

**当前逻辑**：先 `toggleSoftInput(SHOW_FORCED)` 弹出键盘（此时 1x1 焦点窗口仍持有焦点，软键盘绑定到**我们的窗口**），然后才移除窗口。窗口被移除后 IME 连接断开，而 Winlator 从未获得 IME 焦点，输入就丢了。

**修复**：先移除焦点窗口让焦点回到 Winlator，**再**弹出键盘，让 IME 绑定到游戏窗口。

另外发现 `pauseCapturing(removeWindow=true)` 有个隐患：`if (!isCapturing) return` 会导致已暂停（保留窗口）状态下无法移除窗口。一并修复。



现在编译并推送。



已安装。本次修复了键盘输入无法到达 WoW 的问题：

**根因**：之前先弹键盘再移除焦点窗口，导致软键盘绑定到了我们的 1x1 焦点窗口上，窗口移除后 IME 连接断开，Winlator 从未获得键盘输入焦点。

**改动**：
1. [ControllerOverlayService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt) `toggleSystemKeyboard`：改为**先移除 1x1 焦点窗口**，让输入焦点回到 Winlator，延迟 120ms 等焦点转移完成后再弹出键盘，确保 IME 绑定到游戏窗口
2. `pauseCapturing(removeWindow=true)`：修复已暂停状态下无法移除保留窗口的问题（键盘场景即使已暂停也需移除窗口让出焦点）

请验证：按"切换键盘"手柄键 → 键盘应弹出 → 在 WoW 里打字应能正常输入；再按一次应能收起键盘。如果键盘没弹出来（而不是输入不到），也告诉我——那说明 Winlator 需要先点一下游戏窗口才有输入焦点，我会再加一个触摸激活的兜底。

---

**User**

按切换键盘后暂停捕获了，键盘没有弹出，再调用切换捕获没法恢复捕获

**Assistant**

我的修复引入了回归：移除窗口后（1）软键盘无法从服务进程弹出（IME 只能绑定本进程有焦点的窗口），（2）焦点窗口没了手柄按键也到不了应用，导致无法恢复捕获。

先看 TCP 协议和 Windows 端，评估一条更可靠的方案。



协议是简单二进制包，加文本消息可行。看 Windows 端实现确认可行性。



打字到 WoW 的关键约束：IME 只能绑定到**本进程有焦点的窗口**，Winlator 是独立进程，所以软键盘只能绑定到我们的 1x1 窗口；要让它到达 WoW，唯一可靠路径是**捕获 IME 输入 → 通过 TCP 转发到 Windows 端 → SendInput 注入**（也正好保留窗口、恢复捕获不卡死）。

先确认现有 VK 映射可复用。





gcc 可用（M:/msys64/ucrt64），C 端已确认协议。现在我已完整掌握现状，先说明根因，然后直接实施修复方案。

**根因**：上轮"先移除焦点窗口再弹键盘"是错误方向——软键盘只能绑定**本进程有焦点的窗口**，窗口移除后 IME 无法弹出，且手柄按键也到不了应用，所以无法恢复捕获。

**方案**：保留 1x1 焦点窗口，让软键盘绑定到它，捕获 IME 文本/按键后经 TCP 转发到 Windows 用 SendInput 注入 WoW（字符转 VK 键码，无需改 C 端协议）。

开始实施。



Now update BridgeInputInjector to expose the VK mapping and add char→VK conversion.



Let me check for compile errors.



No compile errors. Building and pushing to device.



The file has a brace mismatch — `androidKeyCodeToWindowsVK` at line 168 is missing its closing brace, so `charToWindowsVK` gets nested inside it and the file ends one `}` short. Let me fix all three compile errors.



Now let me verify the ControllerOverlayService code around line 1064 and the toggleSystemKeyboard logic.



Code looks correct. Let me verify the `pauseCapturing`/`resumeCapturing` keep the 1x1 window and the IME callback registration.



Let me check the GamepadInputView layout params (softInputMode) and the file header.



All logic looks correct. Now let me build the APK.



构建成功。现在推送安装到手机。



已修复编译错误并推送成功。本次修复内容：

**修复的回归问题**
之前"按切换键盘后暂停捕获、键盘没弹出、无法恢复捕获"的根因是旧方案先移除了 1x1 焦点窗口再弹键盘——但 IME 软键盘只能绑定本进程有焦点的窗口，窗口没了键盘就弹不出来，且手柄事件也失去了接收窗口。

**新方案（IME 转发链路）**
- 弹键盘时**保留 1x1 焦点窗口**，让软键盘绑定它
- 键盘输入 → [GamepadInputView.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/injection/GamepadInputView.kt) 的 InputConnection 捕获字符 → [ControllerOverlayService.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/service/ControllerOverlayService.kt) 的 `forwardImeChar`/`forwardImeKey` 映射为 Windows VK 键码 → TCP → Windows 端 SendInput 注入 WoW
- 窗口保留，所以暂停后手柄"切换捕获"键仍能恢复捕获

**修复的编译错误**
1. [BridgeInputInjector.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/injection/BridgeInputInjector.kt) `androidKeyCodeToWindowsVK` 缺少函数闭合花括号（导致 `charToWindowsVK` 被嵌套、末尾少一个 `}`）
2. [GamepadInputView.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/injection/GamepadInputView.kt) `performEditorAction` 中 `KeyEvent` 构造参数补 `repeatCount=0`

APK 已安装到手机。请验证：
1. 按"切换键盘"后软键盘是否弹出
2. 键盘输入（字母/数字/符号/回车/退格）能否到达 WoW
3. 键盘显示期间按"切换捕获"能否恢复捕获
4. 隐藏键盘后捕获是否恢复、手柄是否正常

---

**User**

1. 可以弹出 2. 数字 字母 符号能到 退格道不了 3. 可以 4 不能恢复捕获，手柄不正常

**Assistant**

让我检查 mapper 的暂停/恢复逻辑和 IME 相关代码，分析退格无效和恢复捕获异常的问题。



让我检查 SteamInput 的暂停处理逻辑。



让我先拉取 logcat 分析运行时行为，确认退格和恢复捕获问题的实际路径。



logcat 被系统噪音刷屏，app 自身日志已轮转。我基于运行逻辑定位了两个问题的根因：

**问题2（退格无效）**：Gboard 对非编辑框连接用 `sendKeyEvent(KEYCODE_DEL)` 发送退格，而当前 [GamepadInputView.kt](file:///l:/steamlike/app/src/main/java/com/steamlike/controller/injection/GamepadInputView.kt) 的 `sendKeyEvent` 把 DEL 直接丢弃了，导致退格永远到不了。

**问题4（隐藏键盘后无法恢复）**：隐藏键盘后 1x1 焦点窗口可能失去焦点，手柄事件不再路由到它，需要主动重新请求焦点。

让我实施修复。



现在给 ControllerOverlayService 添加隐藏键盘后主动请求焦点，并在 resumeCapturing 中主动恢复焦点，同时加日志确认焦点状态。



给 GamepadInputView 的焦点变化加日志，便于确认问题 4 是否焦点导致。



构建并安装。



已安装。现在启动 logcat 实时采集，让用户测试时我能看到焦点和按键路由日志。



让我先确认代码当前状态（上一轮修复是否已落地），并检查后台 logcat 采集情况。



修复代码已确认落地。检查 `resumeCapturing` 的焦点逻辑和后台 logcat 采集状态。



代码确认无误。查看后台 logcat 是否采集到用户测试数据。



logcat 后台进程从断开的会话继承，看不到实际输出。重启采集并确认设备上有最新 APK，然后让用户重新测试。



设备已连接，APK 是最新构建（14:58）。重新安装到设备，然后启动新的 logcat 采集。



安装成功。清空并启动新的后台 logcat 采集。



最新修复版已重新安装到手机（包含退格修复 + 隐藏键盘后主动恢复焦点），logcat 已在后台实时采集。

请按下面步骤测试：

1. **退格**：按"切换键盘"弹出键盘 → 输入几个字母/数字（应能到 WoW）→ 按退格键，看 WoW 里字符是否被删除
2. **隐藏键盘后恢复**：再按"切换键盘"隐藏键盘 → 手柄操作是否恢复正常 → 按"切换捕获"能否暂停/恢复

测试完告诉我结果，我会从 logcat 分析实际路径（重点看 `GamepadInputView` 的焦点日志和退格是走 `sendKeyEvent` 还是 `deleteSurroundingText`）。

---

**User**

1. 可以删除了，但是键盘按下1个字母出现两个 2. 用切换键盘可以恢复捕捉，但是我点击键盘的隐藏没法恢复捕捉

**Assistant**

收到两个新问题。先看 logcat 里实际发生了什么，同时查看 `forwardImeKey` 的实现。



logcat 确认了问题 2 的根因：`Keyboard hidden, capture resumed` 只在按"切换键盘"键时出现（`toggleSystemKeyboard`），而用户点键盘自身隐藏按钮时**没有任何日志**——说明 app 完全没收到键盘隐藏通知，`isKeyboardShowing` 状态卡住。

先看 `forwardImeKey` 实现和本轮改动，确定重复字母的路径。



minSdk=24，compileSdk=37。看 `GamepadInputView.kt` 相对上次提交的具体改动，确认普通字符路径是否被改动。



git diff 显示整个 IME 转发是新增代码。**重复字母的根因找到了**：`BaseInputConnection(this, false)`（非可编辑连接）的 `commitText`/`sendKeyEvent` 在 `super` 调用时，会通过 `InputMethodManager.sendKeyEvent` 把字符**再派发到本 View 的 `dispatchKeyEvent`** → 又走一遍映射器 → 发到 WoW。即我们转发一次 + super 又派发一次 = 两个字符。

看服务端的相关代码（createGamepadInputWindow 的 IME 回调绑定、onDestroy、字段）。



根因已定位，方案明确。查看两个文件头部的 import 确认可用类。



Let me read the current state of the two files to implement the fixes.



Let me read the `toggleSystemKeyboard` function and related fields in ControllerOverlayService.kt.



Let me check the running logcat for evidence and review the git history of the previous fix round.



Let me look at the git history of GamepadInputView.kt to see exactly what changed in the backspace-fix round.



The InputConnection code is all uncommitted (added in the previous session). The double appeared after the `sendKeyEvent` override was added. Let me check how the mapper handles arbitrary keycodes, and whether letter key events could double-inject.



The mapper filters by gamepad source, so `super.sendKeyEvent` re-dispatch can't inject letters. The double must come from the IME text path itself. Let me verify exactly what AOSP `BaseInputConnection` does on a plain View (no editable) to confirm the re-dispatch behavior.



Found the critical clue: `commitText` calls `sendCurrentText()` which in fallback mode (`fullEditor=false`) dispatches per-character key events to the view. Let me get the full `replaceText`/`setComposingText`/`sendCurrentText` source to confirm the exact double path.



I've confirmed the root cause of the double-letter bug. The AOSP `BaseInputConnection.commitText()` calls `sendCurrentText()`, and in fallback mode (`BaseInputConnection(view, false)` — which is what we use) it dispatches character key events to the view; meanwhile our `sendKeyEvent` override forwards letter keycodes to `onImeKey`, AND `commitText`/`setComposingText` inject via `onImeChar`. Multiple injection paths for the same keystroke = double characters.

Let me implement the fixes in `GamepadInputView.kt`.



The logcat shows the old build behavior — no IME callback logging exists yet. Let me verify the exact AOSP `BaseInputConnection` fallback behavior to pin down the double-letter root cause before implementing.



The logcat confirms the IME callbacks have no logging yet. Let me check the full uncommitted diff to see everything that changed in the "backspace fix" build (since letters worked single before that build, and now they're doubled).



Let me verify the mapper path and check the exact KeyboardMouseMapper onKeyEvent implementation.



Let me verify the exact AOSP `BaseInputConnection` fallback behavior — particularly the source of the re-dispatched event.



The AOSP source confirms the fallback re-dispatch mechanism. My analysis: the re-dispatched events are `SOURCE_KEYBOARD` and get filtered by `SteamInput`'s gamepad-only source check — so they can't double-inject. The real double-letter cause is the `else -> onImeKey?.invoke(event)` branch in `sendKeyEvent` (added in the backspace-fix build): Gboard sends letter keys via **both** `commitText` (text channel) **and** `sendKeyEvent` (key-event channel), so letters get injected twice. Before that override existed, only `commitText` injected → single letter.

Let me now implement both fixes. Starting with `GamepadInputView.kt`.



Now the sendKeyEvent fix (double-letter) and added diagnostics.



Now add the `CONTROL_KEY_CODES` set to the companion object, plus `closeConnection`/`onKeyPreIme` overrides and logging.



Now add logging to the text-channel callbacks and `closeConnection`/`onKeyPreIme` overrides.



Now wire up the service side in `ControllerOverlayService.kt` — register the new callbacks and add `handleImeHidden()`.



Add diagnostics to the forwarding methods in the service.



Check diagnostics for both modified files.



No errors. Building the APK.



`KEYCODE_MOVE_UP/DOWN` don't exist. Removing them.



Build successful. Installing to the device.



Device not detected. Checking adb devices.



---

## 最终结论（更新于 2026-08-22，以下内容覆盖上文各中间阶段的描述）

### 暂停捕获方案（最终版）

- **暂停捕获 = 真正移除 1x1 焦点窗口**（`ControllerOverlayService.pauseCapturing()`），恢复 Android 预测式返回/侧滑返回手势。
- **代价**：移除窗口后手柄按键无法再到达应用，**"切换捕获"手柄键在暂停后无法恢复捕获**。恢复只能通过：
  1. 悬浮窗展开面板 → "恢复捕获"按钮
  2. App 内"手柄捕获"开关
- 上文 998-1007 行"1x1 窗口保留方案"、1134 行"窗口保留，所以暂停后手柄'切换捕获'键仍能恢复捕获"均为**中间过程，已被本方案取代**。

### 无障碍按键过滤方案（已弃用）

- 曾尝试 `GamepadAccessibilityService` + `FLAG_REQUEST_FILTER_KEY_EVENTS` 在暂停时全局接收手柄按键用于恢复捕获。
- **实测这台小米设备未授予该能力**：服务可开启但 `dumpsys accessibility` 显示 `capabilities=0`，系统设置无"按键过滤"开关，`onKeyEvent` 从不触发（MIUI 阉割）。
- 类保留仅用于能力检测，新代码不得依赖。

### 切换键盘（IME 转发链路，最终版）

- 按"切换键盘"弹出软键盘时**保持捕获（不暂停）**，软键盘绑定到 1x1 焦点窗口（IME 只能绑定本进程有焦点的窗口，Winlator 是独立进程无法直接绑定）。
- 输入内容经 `InputConnection` → `forwardImeChar`/`forwardImeKey` 映射为 Windows VK → TCP → Windows 端 `SendInput` 注入 WoW。
- **去重**：只有 `CONTROL_KEY_CODES`（回车/方向/翻页等）走按键事件通道 `onImeKey`；可打印字符仅走文本通道（`commitText`/`setComposingText`），否则 `BaseInputConnection(view, false)` 的 fallback 会重复派发 → "按一个字母出现两个"。
- **退格**：`sendKeyEvent` 中 `KEYCODE_DEL` → `'\b'`（VK_BACK）、`KEYCODE_FORWARD_DEL` → `'\u007F'`（VK_DELETE）。
- **键盘隐藏恢复**：点击键盘自身隐藏按钮/返回键/输入法失活 → `handleImeHidden()` 恢复捕获（insets 变化 / `closeConnection` / `onKeyPreIme` 三路信号驱动，`isKeyboardShowing` 守卫）。
- 上文 1030-1052 行"先移除焦点窗口再弹键盘"为**已被推翻的中间方案**。

### 悬浮窗 / App 界面

- 悬浮窗"暂停/恢复捕获"按钮文字跟随实际捕获状态 `isCapturing`，与 App 内"手柄捕获"开关双向同步（广播 + 持久化）。
- 暂停时收起胶囊显示"⏸"标记。

---

