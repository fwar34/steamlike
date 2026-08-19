package com.steamlike.controller.service

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.steamlike.controller.LayerEditActivity
import com.steamlike.controller.config.AppConfig
import com.steamlike.controller.config.AppConfigStore
import com.steamlike.controller.config.ConfigManager
import com.steamlike.controller.config.ControllerConfig
import com.steamlike.controller.core.SteamInput
import com.steamlike.controller.injection.BridgeInputInjector
import com.steamlike.controller.injection.GamepadInputView
import com.steamlike.controller.injection.InputBridgeServer
import com.steamlike.controller.mapping.KeyboardMouseMapper
import com.steamlike.controller.mapping.WoWActionSets

/**
 * 悬浮窗前台服务（无操作集版本）
 *
 * ## 职责
 * 1. 启动 TCP 桥接服务器，等待 Windows 客户端连接
 * 2. 创建 **全屏透明焦点窗口** (GamepadInputView) 捕获手柄事件
 * 3. 创建 **悬浮窗 UI** 显示状态、操作层堆栈、层切换按钮
 *
 * ## 双窗口架构
 * ```
 * WindowManager
 *  ├─ GamepadInputView (全屏透明, FLAG_NOT_TOUCHABLE, 可获焦点)
 *  │   → 捕获手柄 KeyEvent / MotionEvent
 *  │   → 触摸事件穿透到下层 Winlator
 *  │
 *  └─ 悬浮窗UI (小面板, FLAG_NOT_FOCUSABLE, 可触摸)
 *      → 显示连接状态、操作层堆栈
 *      → 提供层切换按钮（可拖动）
 * ```
 *
 * 注意: 焦点窗口捕获手柄事件后，触摸事件通过 FLAG_NOT_TOUCHABLE 穿透到 Winlator，
 * 用户仍可正常触摸操作 Winlator 界面。
 */
class ControllerOverlayService : Service() {

    companion object {
        private const val TAG = "SteamLikeService"
        const val CHANNEL_ID = "steamlike_controller"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "STOP"
        /** 导出当前配置到指定 URI */
        const val ACTION_EXPORT_CONFIG = "EXPORT_CONFIG"
        /** 从指定 URI 导入配置 */
        const val ACTION_IMPORT_CONFIG = "IMPORT_CONFIG"
        /** 重置为默认配置（删除内部配置文件并重新初始化） */
        const val ACTION_RESET_CONFIG = "RESET_CONFIG"
        /** 暂停悬浮窗（移除焦点窗口和悬浮窗UI，保留TCP服务器和映射器运行） */
        const val ACTION_PAUSE_OVERLAY = "PAUSE_OVERLAY"
        /** 恢复悬浮窗（重新创建焦点窗口和悬浮窗UI） */
        const val ACTION_RESUME_OVERLAY = "RESUME_OVERLAY"
        /** 更新右摇杆优化设置（GlobalSettings） */
        const val ACTION_UPDATE_SETTINGS = "UPDATE_SETTINGS"
        /** 设置捕获开关（MainActivity 调用，extra: EXTRA_CAPTURE_ENABLED） */
        const val ACTION_SET_CAPTURE = "SET_CAPTURE"
        /** 刷新悬浮窗层名（配置导入/重置后调用） */
        const val ACTION_REFRESH_LAYERS = "REFRESH_LAYERS"
        /** 智能暂停开关 (Boolean) */
        const val EXTRA_SMART_PAUSE = "smart_pause"
        /** 捕获白名单包名 (String, 逗号分隔) */
        const val EXTRA_WHITELIST = "capture_whitelist"
        /** 悬浮窗"游戏"按钮拉起应用包名 (String) */
        const val EXTRA_LAUNCHER_PACKAGE = "launcher_package"
        /** 捕获开关 (Boolean) */
        const val EXTRA_CAPTURE_ENABLED = "capture_enabled"
        /** Intent extra: 配置文件 URI */
        const val EXTRA_CONFIG_URI = "config_uri"
        /** Intent extra: TCP监听地址，空表示监听所有接口 */
        const val EXTRA_HOST = "server_host"
        /** Intent extra: TCP监听端口 */
        const val EXTRA_PORT = "server_port"
        /** Intent extra: 摇杆死区 (Float, 0.0~1.0) */
        const val EXTRA_DEADZONE = "deadzone"
        /** Intent extra: 右摇杆视角灵敏度 (Float, 0.1~5.0) */
        const val EXTRA_LOOK_SENSITIVITY = "look_sensitivity"
        /** Intent extra: 光标移动速度倍率 (Float) */
        const val EXTRA_CURSOR_SPEED = "cursor_speed"
        /** Intent extra: 视角平滑系数 (Float, 0.0~0.95) */
        const val EXTRA_LOOK_SMOOTHING = "look_smoothing"
        /** Intent extra: 视角加速曲线指数 (Float, 0.5~3.0) */
        const val EXTRA_LOOK_ACCELERATION = "look_acceleration"

        /** 广播: 客户端连接状态变化 */
        const val ACTION_CLIENT_STATUS = "CLIENT_STATUS"
        /** Intent extra: 连接状态文本 */
        const val EXTRA_STATUS_TEXT = "status_text"
        /** Intent extra: 是否已连接 */
        const val EXTRA_CONNECTED = "connected"

        /** 广播: 捕获状态变化（悬浮窗/MainActivity 双向同步） */
        const val ACTION_CAPTURE_STATUS = "CAPTURE_STATUS"
        /** Intent extra: 是否正在捕获 */
        const val EXTRA_CAPTURING = "capturing"

        /** 智能监控轮询间隔（毫秒） */
        private const val SMART_MONITOR_INTERVAL_MS = 1500L

        /** 前台应用查询时间窗（毫秒） */
        private const val SMART_FOREGROUND_WINDOW_MS = 60_000L

        /** 默认层名匹配（未重命名的层显示预设中文名） */
        private val DEFAULT_LAYER_NAME_REGEX = Regex("Layer\\d+")

        // ===== 悬浮窗配色 =====
        /** 展开面板背景（近实深色，圆角） */
        private const val COLOR_PANEL = 0xF21C1C1C.toInt()
        /** 层按钮正常态背景 */
        private const val COLOR_LAYER_NORMAL = 0xCC333333.toInt()
        /** 层按钮激活态背景（绿色） */
        private const val COLOR_LAYER_ACTIVE = 0xFF4CAF50.toInt()
        /** 功能按钮正常态背景 */
        private const val COLOR_BTN_NORMAL = 0xCC444444.toInt()
        /** 功能按钮按下态背景 */
        private const val COLOR_BTN_PRESSED = 0xCC6E6E6E.toInt()
        /** 游戏按钮背景（主操作，深绿） */
        private const val COLOR_BTN_GAME = 0xFF2E7D32.toInt()
        /** 游戏按钮按下态 */
        private const val COLOR_BTN_GAME_PRESSED = 0xFF388E3C.toInt()
        /** 关闭按钮背景（危险操作，深红） */
        private const val COLOR_BTN_DANGER = 0xCCB71C1C.toInt()
        /** 关闭按钮按下态 */
        private const val COLOR_BTN_DANGER_PRESSED = 0xCCD32F2F.toInt()
        /** 收起胶囊边框 */
        private const val COLOR_COLLAPSED_STROKE = 0x66FFFFFF
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var gamepadInputView: GamepadInputView? = null
    private var statusText: TextView? = null
    private var layerText: TextView? = null
    private var hintText: TextView? = null
    /**
     * "暂停/恢复捕获"按钮引用，用于在 isCapturing 状态变化时更新文本
     */
    private var captureButton: Button? = null
    /**
     * 收起状态下显示当前激活层名的 TextView 引用
     *
     * 用于操作层切换时动态更新悬浮窗文本（替代原静态 🎮 图标）。
     * - 无激活层时显示"公共层"
     * - 多个激活层时显示最上层（最后激活的）名称
     * - 捕获暂停时追加"⏸"标记
     */
    private var collapsedTextView: TextView? = null
    private val layerButtons = mutableMapOf<String, Button>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 悬浮窗收起/展开状态
    private var isExpanded = false
    private var overlayParams: WindowManager.LayoutParams? = null

    /**
     * 最新状态文本缓存
     *
     * 用于解决"展开悬浮窗后仍显示'初始化中'"的问题：
     * startMapper() 在子线程异步完成并调用 updateStatus() 时，
     * 如果用户尚未展开悬浮窗，statusText 为 null，post 的内容被丢弃。
     * 展开时 showExpandedView() 会重新创建 statusText，
     * 通过此字段恢复最近一次的状态文本。
     */
    private var currentStatus: String = "初始化中..."

    private var steamInput: SteamInput? = null
    private var mapper: KeyboardMouseMapper? = null
    private var bridgeServer: InputBridgeServer? = null
    private var configManager: ConfigManager? = null
    /** TCP监听地址，由Intent extra EXTRA_HOST设置，null表示监听所有接口 */
    private var serverHost: String? = null
    /** TCP监听端口，由Intent extra EXTRA_PORT设置 */
    private var serverPort: Int = InputBridgeServer.DEFAULT_PORT

    // ====================================================================
    // 智能暂停（Smart Pause）
    // ====================================================================
    // 可焦点悬浮窗在 Android 13+ 会吃掉系统预测式返回手势（右滑返回失效），
    // 这是系统级行为，应用内无法让返回穿透焦点窗口。
    // 解决方案：监听前台应用（UsageStats），仅当"捕获白名单"内的应用
    // （如 Winlator）或本应用在前台时保持焦点窗口，其余时间自动移除，
    // 让下层应用恢复右滑返回。未授权"使用情况访问"时退化为手动模式。
    // ====================================================================

    /** 智能暂停开关（默认开启，由 MainActivity 设置） */
    @Volatile
    private var smartPauseEnabled: Boolean = true

    /**
     * 捕获总开关（用户手动暂停/恢复，与 MainActivity 开关双向同步）
     *
     * true=允许捕获（智能暂停模式下由前台应用决定窗口是否显示）
     * false=手动暂停（移除焦点窗口，智能监控不再自动恢复）
     */
    @Volatile
    private var captureEnabled: Boolean = true

    /** 捕获白名单包名集合（前台应用在此集合内时保持捕获） */
    @Volatile
    private var captureWhitelist: Set<String> = AppConfig.DEFAULT_WHITELIST.toSet()

    /** 悬浮窗"游戏"按钮拉起的目标应用包名（来自 AppConfig） */
    @Volatile
    private var launcherPackage: String = AppConfig.DEFAULT_LAUNCHER

    /** 智能监控线程运行标志 */
    @Volatile
    private var smartMonitorRunning = false

    private var smartMonitorThread: Thread? = null

    /**
     * 悬浮窗是否被暂停（在 LayerEditActivity 等设置界面打开时移除窗口）
     *
     * ## 作用
     * 全屏透明焦点窗口 (GamepadInputView) 虽然设置 FLAG_NOT_TOUCHABLE，
     * 仍可能拦截系统手势（如边缘滑动返回）。当用户进入设置 Activity 时，
     * 临时移除所有 WindowManager 添加的 View，避免干扰系统手势。
     *
     * ## 调用流程
     * ```
     * LayerEditActivity.onCreate → ACTION_PAUSE_OVERLAY → pauseOverlay()
     *   ↓ 移除 gamepadInputView + overlayView
     * LayerEditActivity.onDestroy → ACTION_RESUME_OVERLAY → resumeOverlay()
     *   ↓ 重新创建 gamepadInputView + overlayView
     * ```
     */
    private var isOverlayPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 14+ 需要指定前台服务类型
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification("启动中..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 从配置文件加载全部运行时配置（服务器/智能暂停/白名单/捕获开关/拉起应用）
        reloadAppConfig()
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 读取TCP监听地址和端口（每次startService都可更新，但仅在首次startMapper时生效）
        intent?.getStringExtra(EXTRA_HOST)?.let { serverHost = it }
        intent?.getIntExtra(EXTRA_PORT, serverPort)?.let { serverPort = it }
        // 读取智能暂停配置（每次startService可更新）
        intent?.getBooleanExtra(EXTRA_SMART_PAUSE, smartPauseEnabled)?.let { smartPauseEnabled = it }
        intent?.getStringExtra(EXTRA_WHITELIST)?.let { captureWhitelist = AppConfig.parseWhitelist(it).toSet() }
        intent?.getStringExtra(EXTRA_LAUNCHER_PACKAGE)?.let { launcherPackage = it }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSmartMonitor()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPORT_CONFIG -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(EXTRA_CONFIG_URI)
                handleExportConfig(uri)
            }
            ACTION_IMPORT_CONFIG -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(EXTRA_CONFIG_URI)
                handleImportConfig(uri)
            }
            ACTION_RESET_CONFIG -> {
                handleResetConfig()
            }
            ACTION_PAUSE_OVERLAY -> {
                pauseOverlay()
            }
            ACTION_RESUME_OVERLAY -> {
                resumeOverlay()
            }
            ACTION_UPDATE_SETTINGS -> {
                handleUpdateSettings(intent)
            }
            ACTION_SET_CAPTURE -> {
                // MainActivity 捕获开关变化 → 应用到服务并广播同步
                intent.getBooleanExtra(EXTRA_CAPTURE_ENABLED, captureEnabled)?.let {
                    setCaptureEnabled(it)
                }
            }
            ACTION_REFRESH_LAYERS -> {
                refreshLayerNames()
            }
        }
        // 首次启动时初始化映射器
        if (steamInput == null) {
            startMapper()
        } else {
            // 映射器已就绪：智能暂停配置可能已更新，重启监控以生效
            restartSmartMonitor()
        }
        return START_STICKY
    }

    private fun startMapper() {
        // DisplayMetrics 必须在主线程获取
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)

        // 在后台线程执行，避免 ServerSocket.bind() 触发 NetworkOnMainThreadException
        Thread {
            try {
                // 启动TCP桥接服务器（网络操作，必须在子线程）
                bridgeServer = InputBridgeServer(serverHost, serverPort)
                bridgeServer?.onClientConnected = { addr ->
                    Log.i(TAG, "Client connected: $addr")
                    updateStatus("✅ 客户端已连接: $addr")
                    broadcastClientStatus("客户端: $addr", true)
                }
                bridgeServer?.onClientDisconnected = { addr ->
                    Log.i(TAG, "Client disconnected: $addr")
                    val msg = waitMessage()
                    updateStatus(msg)
                    broadcastClientStatus(msg, false)
                }
                bridgeServer?.onServerError = { msg ->
                    Log.e(TAG, "Server error: $msg")
                    updateStatus("❌ 服务器错误: $msg")
                    broadcastClientStatus("服务器错误: $msg", false)
                }

                if (bridgeServer?.start() != true) {
                    Log.e(TAG, "TCP server start failed")
                    updateStatus("❌ TCP 服务器启动失败")
                    broadcastClientStatus("TCP 服务器启动失败", false)
                    return@Thread
                }
                Log.i(TAG, "TCP server started on ${serverHost ?: "0.0.0.0"}:${serverPort}")

                // 使用桥接注入器（通过TCP发送事件到Windows客户端）
                val injector = BridgeInputInjector(bridgeServer!!)

                steamInput = SteamInput(this)
                // 将 SteamInput 实例暴露给 LayerEditActivity，供操作层设置界面读写配置
                LayerEditActivity.steamInputRef = steamInput
                configManager = ConfigManager(this, steamInput!!)

                mapper = KeyboardMouseMapper(
                    steamInput = steamInput!!,
                    injector = injector,
                    screenWidth = metrics.widthPixels,
                    screenHeight = metrics.heightPixels
                )

                mapper?.onLayerChanged = { layers ->
                    updateLayerText(layers)
                    updateLayerButtonColors(layers)
                    updateCollapsedViewText(layers)
                }

                if (mapper?.start() == true) {
                    Log.i(TAG, "Mapper started successfully")
                    // 加载用户配置（覆盖默认WoW预设的绑定和属性）
                    // 动作定义和回调保持不变，仅修改绑定关系
                    loadUserConfig()

                    // 创建焦点输入窗口（addView 必须在主线程执行）
                    mainHandler.post { createGamepadInputWindow() }

                    // 启动智能暂停监控（检测前台应用自动移除/恢复焦点窗口）
                    startSmartMonitor()

                    val waitMsg = waitMessage()
                    updateStatus(waitMsg)
                    broadcastClientStatus(waitMsg, false)
                    updateLayerText(mapper?.getActiveLayers() ?: emptyList())
                    updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())
                } else {
                    Log.e(TAG, "Mapper start failed - check overlay permission")
                    updateStatus("❌ 启动失败 - 请检查悬浮窗权限")
                    broadcastClientStatus("启动失败 - 请检查悬浮窗权限", false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startMapper error", e)
                updateStatus("❌ 错误: ${e.message}")
                broadcastClientStatus("错误: ${e.message}", false)
            }
        }.start()
    }

    /**
     * 等待 Windows 客户端连接的状态文本（悬浮窗/通知/主界面共用）
     */
    private fun waitMessage(): String =
        "⏳ 等待 Windows 客户端连接（${serverHost ?: "0.0.0.0"}:${serverPort}）"

    // ====================================================================
    // 配置管理
    // ====================================================================
    // 服务通过 Intent action 接收来自 MainActivity 的配置操作请求:
    // - ACTION_EXPORT_CONFIG: 导出当前配置到用户选择的 URI
    // - ACTION_IMPORT_CONFIG: 从用户选择的 URI 导入配置
    // - ACTION_RESET_CONFIG:  重置为默认配置（删除内部配置文件并重新初始化）
    //
    // 配置文件存储在 {filesDir}/steamlike_config.json，
    // 服务启动时自动加载（覆盖默认 WoW 预设的绑定和属性）。
    // ====================================================================

    /**
     * 加载内部配置文件并应用
     *
     * 在 [KeyboardMouseMapper.start] 之后调用，用用户保存的配置覆盖默认 WoW 预设。
     *
     * ## 调用时机
     * ```
     * startMapper():
     *   ① mapper.start() → WoWActionSets.setup() 加载代码中的默认配置
     *   ② loadUserConfig() → 读取内部配置文件，覆盖默认配置  ← 此方法
     *   ③ createGamepadInputWindow() → 创建焦点窗口
     * ```
     *
     * ## 行为说明
     * - 如果内部配置文件不存在，使用默认 WoW 预设（步骤①的配置）
     * - 如果配置文件存在，[ConfigManager.applyConfig] 会:
     *   - 清除默认绑定 → 应用配置文件中的绑定
     *   - 动作定义和回调不受影响（它们在代码中定义）
     * - 加载过程中的警告会输出到 Logcat（标签: ConfigManager）
     */
    private fun loadUserConfig() {
        val si = steamInput ?: return
        val cm = configManager ?: return
        // 从内部存储加载配置（不存在则使用默认配置，自动应用到 SteamInput）
        cm.loadFromInternal()
    }

    /**
     * 处理导出配置请求
     *
     * 将当前 SteamInput 的运行时状态导出为 JSON，写入用户通过 SAF 选择的 URI。
     * 同时保存到内部存储（自动持久化）。
     *
     * ## 流程
     * ```
     * MainActivity 点击"导出配置"
     *      ↓ SAF CreateDocument 选择保存位置
     *      ↓ 发送 ACTION_EXPORT_CONFIG intent + URI
     * ControllerOverlayService.handleExportConfig(uri)
     *      ↓ configManager.exportConfig(steamInput) 提取当前配置
     *      ↓ configManager.saveToUri(config, uri) 写入用户选择的位置
     *      ↓ configManager.saveToFile(config) 同步到内部存储
     *      ↓ Toast 提示导出结果
     * ```
     *
     * @param uri 用户通过 SAF 选择的输出文件 URI（可能为 null）
     */
    private fun handleExportConfig(uri: Uri?) {
        val si = steamInput ?: run {
            toast("映射器未启动，请先启动手柄映射")
            return
        }
        val cm = configManager ?: ConfigManager(this, si)
        if (uri == null) {
            toast("导出失败: 无效的文件路径")
            return
        }
        try {
            val profile = si.profile
            // 导出到用户选择的位置（saveToUri 使用当前 steamInput.profile）
            cm.saveToUri(uri)
            // 同时保存到内部存储（确保自动持久化）
            cm.saveToInternal(profile)
            toast("配置已导出 (${profile.commonLayer.buttonMappings.size}个绑定, ${profile.layers.size}个层)")
        } catch (e: Exception) {
            toast("导出失败: ${e.message}")
        }
    }

    /**
     * 处理导入配置请求
     *
     * 从用户通过 SAF 选择的 URI 读取 JSON 配置，应用到 SteamInput，
     * 并保存到内部存储（下次启动自动加载）。
     *
     * ## 流程
     * ```
     * MainActivity 点击"导入配置"
     *      ↓ SAF OpenDocument 选择配置文件
     *      ↓ 发送 ACTION_IMPORT_CONFIG intent + URI
     * ControllerOverlayService.handleImportConfig(uri)
     *      ↓ configManager.loadFromUri(uri) 读取并解析 JSON
     *      ↓ configManager.applyConfig(steamInput, config) 验证并应用
     *      ↓ configManager.saveToFile(config) 保存到内部存储
     *      ↓ 更新悬浮窗 UI 显示
     *      ↓ Toast 提示导入结果（成功数/跳过数/警告）
     * ```
     *
     * @param uri 用户通过 SAF 选择的输入文件 URI（可能为 null）
     */
    private fun handleImportConfig(uri: Uri?) {
        val si = steamInput ?: run {
            toast("映射器未启动，请先启动手柄映射")
            return
        }
        val cm = configManager ?: ConfigManager(this, si)
        if (uri == null) {
            toast("导入失败: 无效的文件路径")
            return
        }
        try {
            // 从 URI 读取、解析、应用到 SteamInput、保存到内部存储
            val json = contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: run {
                toast("导入失败: 无法读取配置文件")
                return
            }
            // 应用按键映射
            val profile = ControllerConfig.fromJson(json)
            si.loadProfile(profile)
            // 应用运行时配置（settings: 白名单/智能暂停/捕获开关/拉起应用等）
            val importedCfg = ControllerConfig.appConfigFromJsonString(json)
            AppConfigStore.save(this, importedCfg)
            reloadAppConfig()
            restartSmartMonitor()
            updateCaptureButtonState()
            // 持久化（含导入的 settings）
            cm.saveToInternal(profile)
            toast("配置已导入")

            // 更新悬浮窗的层显示（导入可能修改了层定义）
            updateLayerText(mapper?.getActiveLayers() ?: emptyList())
            updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())
            refreshLayerNames()
        } catch (e: Exception) {
            toast("导入失败: ${e.message}")
        }
    }

    /**
     * 处理重置配置请求
     *
     * 删除内部配置文件，并重新初始化映射器以恢复代码中定义的默认 WoW 预设。
     *
     * ## 流程
     * ```
     * MainActivity 点击"重置为默认配置"
     *      ↓ 发送 ACTION_RESET_CONFIG intent
     * ControllerOverlayService.handleResetConfig()
     *      ↓ configManager.deleteConfigFile() 删除内部配置文件
     *      ↓ Toast 提示正在重置
     *      ↓ mapper.stop() 停止当前映射器
     *      ↓ startMapper() 重新初始化（加载默认配置，无配置文件则不覆盖）
     * ```
     */
    private fun handleResetConfig() {
        val si = steamInput ?: run {
            toast("映射器未启动，请先启动手柄映射")
            return
        }
        val cm = configManager ?: ConfigManager(this, si)
        // 重置为默认配置（应用到 SteamInput 并保存到内部存储）
        cm.resetToDefault()
        toast("已重置为默认配置，正在重新初始化...")

        // 停止并重新启动映射器
        mapper?.stop()
        mapper = null
        steamInput = null
        // 清除 LayerEditActivity 的 SteamInput 引用（startMapper 会重新设置）
        LayerEditActivity.steamInputRef = null
        startMapper()
        // 刷新悬浮窗层名（重置后恢复默认名）
        refreshLayerNames()
    }

    /**
     * 处理右摇杆优化设置更新
     *
     * 从 Intent 读取 5 个 Float 参数，更新 SteamInput.profile.globalSettings，
     * 并保存到内部配置文件（持久化），下次启动自动加载。
     *
     * 参数缺省时使用当前 profile 中已有的值（保持不变）。
     */
    private fun handleUpdateSettings(intent: Intent) {
        val si = steamInput ?: run {
            toast("映射器未启动，请先启动手柄映射")
            return
        }
        val old = si.profile.globalSettings
        val newSettings = com.steamlike.controller.core.GlobalSettings(
            deadzone = intent.getFloatExtra(EXTRA_DEADZONE, old.deadzone),
            lookSensitivity = intent.getFloatExtra(EXTRA_LOOK_SENSITIVITY, old.lookSensitivity),
            cursorSpeed = intent.getFloatExtra(EXTRA_CURSOR_SPEED, old.cursorSpeed),
            lookSmoothing = intent.getFloatExtra(EXTRA_LOOK_SMOOTHING, old.lookSmoothing),
            lookAcceleration = intent.getFloatExtra(EXTRA_LOOK_ACCELERATION, old.lookAcceleration)
        )
        // 更新运行时 profile（loadProfile 会重置操作层状态，设置更新时合理）
        si.loadProfile(si.profile.copy(globalSettings = newSettings))
        // 持久化到内部配置文件
        val cm = configManager ?: ConfigManager(this, si).also { configManager = it }
        cm.saveToInternal(si.profile)
        Log.i(TAG, "GlobalSettings updated: deadzone=${newSettings.deadzone}, " +
                "sensitivity=${newSettings.lookSensitivity}, " +
                "smoothing=${newSettings.lookSmoothing}, " +
                "acceleration=${newSettings.lookAcceleration}")
        toast("右摇杆设置已保存（灵敏度=${newSettings.lookSensitivity}, 平滑=${newSettings.lookSmoothing}）")
    }

    /**
     * 在主线程显示 Toast 提示，同时输出到 Logcat
     *
     * 配置操作可能在非主线程执行，需要 post 到主线程才能更新 UI。
     * 所有提示消息同时输出到 Logcat（标签: SteamLikeService），方便调试。
     *
     * @param msg 提示消息
     */
    private fun toast(msg: String) {
        Log.i(TAG, "Toast: $msg")
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 广播客户端连接状态给 MainActivity
     *
     * 当 Windows 客户端连接/断开时，发送广播让 Activity 更新 UI 状态显示。
     * Activity 通过注册 [ACTION_CLIENT_STATUS] 广播接收器来监听状态变化。
     *
     * @param statusText 状态描述文本
     * @param connected 是否已连接
     */
    private fun broadcastClientStatus(statusText: String, connected: Boolean) {
        val intent = Intent(ACTION_CLIENT_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_TEXT, statusText)
            putExtra(EXTRA_CONNECTED, connected)
        }
        sendBroadcast(intent)
    }

    // ===== 悬浮窗暂停/恢复 =====

    /**
     * 暂停悬浮窗（移除焦点窗口和悬浮窗UI）
     *
     * 由 LayerEditActivity onCreate 调用，移除所有 WindowManager 添加的 View：
     * - GamepadInputView（全屏透明焦点窗口，会拦截边缘返回手势）
     * - overlayView（悬浮窗 UI）
     *
     * 保留 TCP 服务器和映射器运行，仅移除窗口视图。
     * 用户退出 LayerEditActivity 时调用 [resumeOverlay] 重建窗口。
     */
    private fun pauseOverlay() {
        if (isOverlayPaused) return
        isOverlayPaused = true
        mainHandler.post {
            gamepadInputView?.let { windowManager?.removeView(it) }
            gamepadInputView = null
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
            Log.i(TAG, "Overlay paused (windows removed)")
        }
    }

    /**
     * 恢复悬浮窗（重新创建焦点窗口和悬浮窗UI）
     *
     * 由 LayerEditActivity onDestroy 调用，重新创建被 [pauseOverlay] 移除的窗口。
     * - 重建 GamepadInputView 捕获手柄事件
     * - 重建悬浮窗 UI 显示状态
     */
    private fun resumeOverlay() {
        if (!isOverlayPaused) return
        isOverlayPaused = false
        mainHandler.post {
            // 重新创建焦点输入窗口（pauseOverlay 已将 gamepadInputView 置 null）
            if (gamepadInputView == null && mapper != null) {
                createGamepadInputWindow()
            }
            // 重新创建悬浮窗窗口（pauseOverlay 已将 overlayView 置 null）
            // createOverlay 内部会创建常驻容器并显示收起胶囊
            if (overlayView == null) {
                createOverlay()
            }
            Log.i(TAG, "Overlay resumed (windows recreated)")
        }
    }

    // ===== 焦点输入窗口 =====

    /**
     * 当前是否正在捕获手柄事件（GamepadInputView 存在并持有焦点）
     *
     * true: GamepadInputView 存在，可接收手柄事件，但会阻止系统返回手势
     * false: GamepadInputView 已移除，系统返回手势恢复工作，但无法接收手柄事件
     *
     * 用户通过悬浮窗的"暂停捕获/恢复捕获"按钮切换。
     */
    @Volatile
    private var isCapturing: Boolean = false

    /**
     * 创建全屏透明焦点窗口捕获手柄事件
     *
     * - 1x1 像素: 不覆盖屏幕，避免遮挡其他应用 UI
     * - FLAG_NOT_TOUCHABLE: 触摸穿透到下层应用
     * - 可获焦点: 接收手柄KeyEvent和MotionEvent
     *
     * 注意: 有焦点的 TYPE_APPLICATION_OVERLAY 窗口会让 Android 14+ 预测式返回手势
     * 失效（系统认为有窗口可能要处理返回键）。因此需要提供"暂停捕获"按钮，
     * 用户需要右滑返回时手动暂停。
     */
    private fun createGamepadInputWindow() {
        if (isOverlayPaused) return
        if (gamepadInputView != null) return  // 已存在，避免重复创建
        gamepadInputView = GamepadInputView(this).also { view ->
            // 转发手柄事件到 KeyboardMouseMapper → SteamInput
            view.onKeyEvent = { event ->
                mapper?.onKeyEvent(event) ?: false
            }
            view.onGenericMotion = { event ->
                mapper?.onGenericMotionEvent(event) ?: false
            }
        }

        val params = GamepadInputView.createLayoutParams()
        windowManager?.addView(gamepadInputView, params)
        isCapturing = true

        // 请求焦点以接收手柄按键事件
        gamepadInputView?.post {
            gamepadInputView?.requestFocus()
        }
        Log.i(TAG, "GamepadInputView created (capturing enabled)")
    }

    /**
     * 移除焦点输入窗口，释放焦点（暂停手柄捕获）
     *
     * 用户需要右滑返回其他应用时调用此方法。移除后系统返回手势恢复正常。
     * 与 [pauseOverlay] 不同，本方法只移除 GamepadInputView，保留悬浮窗 UI
     * 和 TCP 服务器运行，方便用户快速恢复。
     */
    private fun removeGamepadInputWindow() {
        gamepadInputView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove GamepadInputView", e)
            }
        }
        gamepadInputView = null
        isCapturing = false
        Log.i(TAG, "GamepadInputView removed (capturing paused)")
    }

    /**
     * 暂停手柄捕获（移除 GamepadInputView）
     *
     * 用户点击悬浮窗"暂停捕获"按钮时调用。
     * 保留悬浮窗 UI 和 TCP 服务器，仅停止捕获手柄事件。
     */
    fun pauseCapturing() {
        if (!isCapturing) return
        removeGamepadInputWindow()
        // 同步刷新展开视图的按钮文本（如果在展开状态）
        updateCaptureButtonState()
    }

    /**
     * 恢复手柄捕获（重新创建 GamepadInputView）
     *
     * 用户点击悬浮窗"恢复捕获"按钮时调用。
     */
    fun resumeCapturing() {
        if (isCapturing) return
        createGamepadInputWindow()
        // 同步刷新展开视图的按钮文本
        updateCaptureButtonState()
    }

    /**
     * 设置捕获总开关（悬浮窗按钮 / MainActivity 开关共用入口）
     *
     * 与 app 内部开关状态双向同步：
     * - 持久化到配置文件（AppConfigStore，MainActivity 下次启动读取）
     * - 广播 [ACTION_CAPTURE_STATUS]（MainActivity 实时刷新开关）
     *
     * @param enabled true=恢复捕获, false=暂停捕获
     */
    private fun setCaptureEnabled(enabled: Boolean) {
        if (captureEnabled == enabled) return
        captureEnabled = enabled
        // 持久化到配置文件（保留其他运行时配置不变）
        val cfg = AppConfigStore.load(this).copy(captureEnabled = enabled)
        AppConfigStore.save(this, cfg)
        Log.i(TAG, "Capture switch: $enabled")
        if (enabled) {
            // 智能监控运行时由它决定是否恢复（依据前台应用）；未运行
            // （智能暂停关闭/未授权手动模式）时直接恢复捕获
            if (!smartMonitorRunning) {
                mainHandler.post { resumeCapturing() }
            }
        } else {
            // 手动暂停：移除焦点窗口（下层应用恢复右滑返回）
            mainHandler.post { pauseCapturing() }
        }
        broadcastCaptureStatus(enabled)
        updateCaptureButtonState()
    }

    /**
     * 广播捕获状态给 MainActivity（用于同步 app 内开关）
     */
    private fun broadcastCaptureStatus(capturing: Boolean) {
        val intent = Intent(ACTION_CAPTURE_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CAPTURING, capturing)
        }
        sendBroadcast(intent)
    }

    /**
     * 拉起配置的应用（悬浮窗"游戏"按钮）
     *
     * 通过 [launcherPackage]（AppConfig 配置，默认 com.winlator）拉起目标应用，
     * 例如从桌面快速回到 Winlator 游戏。
     * 拥有 SYSTEM_ALERT_WINDOW 权限的应用从后台启动 Activity 属于豁免场景，
     * 不受 Android 10+ 后台启动限制。
     */
    private fun launchGameApp() {
        val pkg = launcherPackage
        if (pkg.isBlank()) {
            toast("未配置拉起应用包名，请在 App 内设置")
            return
        }
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.i(TAG, "Launch app: $pkg")
            } else {
                toast("未找到应用 $pkg，请在 App 内检查拉起应用包名")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app $pkg", e)
            toast("拉起 $pkg 失败: ${e.message}")
        }
    }

    /**
     * 刷新展开视图中"暂停/恢复捕获"按钮的文本
     */
    private fun updateCaptureButtonState() {
        captureButton?.post {
            captureButton?.text = if (captureEnabled) "暂停捕获" else "恢复捕获"
        }
        // 收起视图也同步更新（在层名后追加"⏸"标记）
        if (!isExpanded) {
            updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())
        }
    }

    // ====================================================================
    // 智能暂停监控（Smart Pause Monitor）
    // ====================================================================

    /**
     * 启动智能暂停监控线程
     *
     * 每 [SMART_MONITOR_INTERVAL_MS] 轮询一次前台应用：
     * - 前台应用在捕获白名单内（或为本应用）→ 需要捕获 → 恢复焦点窗口
     * - 否则 → 暂停捕获（移除焦点窗口，下层应用恢复右滑返回）
     *
     * 未授权"使用情况访问"时无法查询前台应用，自动退化为手动模式
     * （监控线程停止，保留悬浮窗手动按钮）。
     */
    private fun startSmartMonitor() {
        if (smartMonitorRunning) return
        if (!smartPauseEnabled) return
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "Smart pause disabled: no usage stats permission")
            updateStatus("智能暂停不可用（未授权使用情况访问），已用手动模式")
            return
        }
        smartMonitorRunning = true
        smartMonitorThread = Thread({
            while (smartMonitorRunning) {
                try {
                    // 手动暂停（captureEnabled=false）时监控不动作，
                    // 避免自动恢复覆盖用户的暂停意图
                    if (captureEnabled) {
                        val fg = getForegroundPackage()
                        if (fg != null) {
                            // 仅白名单应用（如 Winlator）在前台时保持捕获。
                            // 本应用自身在前台时也要暂停捕获（needCapture=false），
                            // 否则应用内界面（MainActivity/操作层设置等）右滑返回
                            // 会被焦点窗口吃掉。
                            val needCapture = captureWhitelist.contains(fg)
                            if (needCapture && !isCapturing) {
                                Log.i(TAG, "Smart pause: foreground=$fg, resuming capture")
                                mainHandler.post { resumeCapturing() }
                            } else if (!needCapture && isCapturing) {
                                Log.i(TAG, "Smart pause: foreground=$fg, pausing capture")
                                mainHandler.post { pauseCapturing() }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Smart monitor error, falling back to manual", e)
                    break
                }
                try {
                    Thread.sleep(SMART_MONITOR_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
            smartMonitorRunning = false
            Log.i(TAG, "Smart monitor stopped")
        }, "SteamLike-SmartMonitor").apply { isDaemon = true }
        smartMonitorThread?.start()
        Log.i(TAG, "Smart monitor started, whitelist=$captureWhitelist")
    }

    /**
     * 停止智能暂停监控线程
     */
    private fun stopSmartMonitor() {
        smartMonitorRunning = false
        smartMonitorThread?.interrupt()
        smartMonitorThread?.join(500)
        smartMonitorThread = null
    }

    /**
     * 重启智能暂停监控（配置更新后调用，幂等）
     */
    private fun restartSmartMonitor() {
        stopSmartMonitor()
        startSmartMonitor()
        // 同步悬浮窗"暂停/恢复捕获"按钮状态
        updateCaptureButtonState()
    }

    /**
     * 查询当前前台应用包名（UsageStats）
     *
     * 通过最近 60 秒的 UsageEvents 取最后一条 ACTIVITY_RESUMED/MOVE_TO_FOREGROUND 事件。
     *
     * @return 前台包名；无法确定时返回 null
     * @throws SecurityException 未授权"使用情况访问"时抛出
     */
    private fun getForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - SMART_FOREGROUND_WINDOW_MS, end)
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                foreground = event.packageName
            }
        }
        return foreground
    }

    /**
     * 检查"使用情况访问"权限是否已授权
     */
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 从配置文件重新加载全部运行时配置
     *
     * 在 onCreate 和配置导入后调用，读取 steamlike_config.json 的 settings：
     * 服务器地址/端口、智能暂停开关、捕获白名单、捕获开关、拉起应用包名。
     * 注意：serverHost/serverPort 仅在首次 startMapper 时生效，运行中修改需重启服务。
     */
    private fun reloadAppConfig() {
        val cfg = AppConfigStore.load(this)
        serverHost = cfg.serverHost
        serverPort = cfg.serverPort
        smartPauseEnabled = cfg.smartPauseEnabled
        captureWhitelist = cfg.captureWhitelist.toSet()
        captureEnabled = cfg.captureEnabled
        launcherPackage = cfg.launcherPackage
        Log.i(TAG, "AppConfig loaded: host=$serverHost port=$serverPort " +
            "smartPause=$smartPauseEnabled whitelist=$captureWhitelist capture=$captureEnabled launcher=$launcherPackage")
    }

    // ===== 悬浮窗 UI =====

    /**
     * 创建悬浮窗（初始为收起状态）
     *
     * 悬浮窗有两种状态:
     * - 收起: 显示一个小图标按钮，可拖动移动位置，点击展开
     * - 展开: 显示完整的操作层面板（状态、层按钮、提示），可拖动
     *
     * 两种状态共享 [overlayParams] 的位置坐标，切换时保持位置不变。
     */
    private fun createOverlay() {
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        // 常驻单窗口容器（FrameLayout）：收起/展开只切换内部子视图，
        // 不再 removeView/addView 重建窗口，避免系统窗口动画造成的闪烁
        val frame = FrameLayout(this)
        overlayView = frame
        overlayParams?.let { windowManager?.addView(frame, it) }
        setupOverlayTouchListener(frame)
        // 初始显示收起胶囊
        showCollapsedView()
    }

    /**
     * 显示收起状态的悬浮窗
     *
     * 收起状态显示一个小胶囊（当前激活层名），点击展开。
     */
    private fun showCollapsedView() {
        val frame = overlayView as? FrameLayout ?: return
        if (isExpanded && frame.childCount > 0) {
            // 展开面板先缩小淡出（离场动画），动画结束后切换到收起胶囊
            val panel = frame.getChildAt(0)
            panel.animate()
                .scaleX(0.6f)
                .scaleY(0.6f)
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { showCollapsedNow(frame) }
                .start()
        } else {
            showCollapsedNow(frame)
        }
    }

    /**
     * 实际切换到收起状态（单窗口内替换子视图）
     *
     * @param frame 悬浮窗常驻容器
     */
    private fun showCollapsedNow(frame: FrameLayout) {
        if (isOverlayPaused) return  // 悬浮窗暂停中不重建

        // 移除旧子视图（与新增同帧完成，无空白帧）
        frame.removeAllViews()

        // 创建收起视图（圆角胶囊：显示当前激活层名，点击展开）
        val collapsed = TextView(this).apply {
            text = buildCollapsedText(mapper?.getActiveLayers() ?: emptyList())
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            // 圆角胶囊背景 + 细边框
            background = roundedDrawable(
                color = 0xE6222222.toInt(),
                cornerRadius = dp(18),
                strokeColor = COLOR_COLLAPSED_STROKE,
                strokeWidth = dp(1)
            )
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        collapsedTextView = collapsed
        frame.addView(collapsed, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // 轻量进入动画：起始可见（alpha 0.4 + 缩放 0.9），避免首帧透明造成闪烁
        collapsed.alpha = 0.4f
        collapsed.scaleX = 0.9f
        collapsed.scaleY = 0.9f
        collapsed.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
        isExpanded = false
    }

    /**
     * 悬浮窗进入动画（淡入 + 缩放 + 下移回弹）
     *
     * 用于展开/收起状态切换时的过渡。时长与位移调大，保证动效清晰可见。
     *
     * @param view 刚添加的新视图
     */
    private fun animateOverlayIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.translationY = -dp(40).toFloat()
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * 根据激活层列表构建收起悬浮窗的显示文本
     *
     * - 空列表（无激活层）: 显示"公共层"
     * - 单个激活层: 显示该层名
     * - 多个激活层: 显示最上层名（最后激活的）
     *
     * @param activeLayers 激活层名称列表
     * @return 显示文本
     */
    private fun buildCollapsedText(activeLayers: List<String>): String {
        // 优先显示中文/自定义显示名，未匹配到则用内部名
        val displayMap = getLayerDisplayNames().toMap()
        val layerName = if (activeLayers.isEmpty()) {
            "公共层"
        } else {
            displayMap[activeLayers.last()] ?: activeLayers.last()
        }
        // 捕获暂停时追加 ⏸ 标记，提醒用户手柄映射未工作
        return if (isCapturing) layerName else "$layerName ⏸"
    }

    /**
     * 更新收起悬浮窗的层名显示
     *
     * 操作层切换时调用，让用户无需展开即可知道当前激活的层。
     * 仅在收起状态下生效（展开状态由 layerText 显示完整堆栈）。
     *
     * @param activeLayers 激活层名称列表
     */
    private fun updateCollapsedViewText(activeLayers: List<String>) {
        collapsedTextView?.post {
            collapsedTextView?.text = buildCollapsedText(activeLayers)
        }
    }

    /**
     * 显示展开状态的悬浮窗
     *
     * 移除收起视图，创建完整的操作层面板。
     * 面板包含"收起"按钮可切换回收起状态。
     */
    private fun showExpandedView() {
        val frame = overlayView as? FrameLayout ?: return
        // 移除收起胶囊（与新增面板同帧完成，无空白帧）
        frame.removeAllViews()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 圆角深色面板
            background = roundedDrawable(COLOR_PANEL, dp(14))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // 面板标题
        container.addView(TextView(this).apply {
            text = "SteamLike 手柄"
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, dp(2))
        })

        // 状态（恢复最近一次的状态文本，避免展开后仍显示"初始化中"）
        statusText = TextView(this).apply {
            text = currentStatus
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            setPadding(0, 0, 0, dp(2))
        }
        container.addView(statusText)

        // 操作层堆栈
        layerText = TextView(this).apply {
            text = ""
            setTextColor(0xFF9FA8FF.toInt())
            textSize = 10f
            setPadding(0, dp(2), 0, dp(4))
        }
        container.addView(layerText)

        // 操作层按钮 (2列 x 5行)，层名与 app 内部配置动态同步
        getLayerDisplayNames().chunked(2).forEach { rowLayers ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, 0)
            }
            rowLayers.forEach { (name, display) ->
                row.addView(createLayerButton(name, display))
            }
            container.addView(row)
        }

        // ===== 控制按钮（分组布局）=====
        // 主操作行：游戏（绿色主按钮） + 暂停/恢复捕获
        val primaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        primaryRow.addView(
            createOverlayButton(
                label = "游戏",
                onClick = { launchGameApp() },
                weight = 1.2f,
                normalColor = COLOR_BTN_GAME,
                pressedColor = COLOR_BTN_GAME_PRESSED,
                textSize = 12f
            )
        )
        // 暂停/恢复捕获按钮（始终显示，与 app 内开关状态双向同步）
        captureButton = createOverlayButton(
            label = "暂停捕获",
            onClick = { setCaptureEnabled(!captureEnabled) },
            weight = 1f,
            textSize = 12f
        )
        primaryRow.addView(captureButton!!)
        container.addView(primaryRow)

        // 次操作行：清除层 + 收起 + 关闭（关闭红色）
        val secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, 0)
        }
        secondaryRow.addView(
            createOverlayButton("清除层", { mapper?.clearAllLayers() }, weight = 1f, textSize = 11f)
        )
        secondaryRow.addView(
            createOverlayButton("收起", { showCollapsedView() }, weight = 1f, textSize = 11f)
        )
        secondaryRow.addView(
            createOverlayButton(
                label = "关闭",
                onClick = { stopSelf() },
                weight = 1f,
                normalColor = COLOR_BTN_DANGER,
                pressedColor = COLOR_BTN_DANGER_PRESSED,
                textSize = 11f
            )
        )
        container.addView(secondaryRow)

        // 快捷键提示
        hintText = TextView(this).apply {
            text = "LB+方向键/A/B/X/Y/L3/R3 切层\nLB+HOME 清全部\n组合键: A+RB=选怪 D-Pad+L3=栏5-8 D-Pad+R3=栏9/0/-/="
            setTextColor(0xFF8A8A8A.toInt())
            textSize = 9f
            setPadding(0, dp(5), 0, 0)
        }
        container.addView(hintText)

        // 添加到常驻容器（frame 已有拖动/点击监听）
        frame.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        // 收起→展开：淡入缩放动画
        animateOverlayIn(container)
        isExpanded = true

        // 刷新当前状态（展开后显示最新状态）
        if (mapper != null) {
            updateLayerText(mapper?.getActiveLayers() ?: emptyList())
            updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())
        }
        // 同步"暂停/恢复捕获"按钮文本（与捕获开关一致）
        captureButton?.text = if (captureEnabled) "暂停捕获" else "恢复捕获"
    }

    /**
     * 为悬浮窗常驻容器设置拖动和点击监听
     *
     * - 拖动: 移动悬浮窗位置（收起和展开状态都支持）
     * - 点击: 收起状态下未移动的点击触发展开；展开状态下不拦截按钮点击
     *
     * @param view 悬浮窗常驻容器（FrameLayout）
     */
    private fun setupOverlayTouchListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var hasMoved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams?.x ?: 0
                    initialY = overlayParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        hasMoved = true
                        overlayParams?.let { params ->
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager?.updateViewLayout(view, params)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // 收起状态下，未移动的点击 = 展开
                    if (!hasMoved && !isExpanded) {
                        showExpandedView()
                        return@setOnTouchListener true
                    }
                }
            }
            // 移动了则消费事件（阻止按钮误触）；未移动则返回 false 让按钮处理
            hasMoved
        }
    }

    private fun createLayerButton(name: String, display: String): Button {
        val btn = Button(this).apply {
            text = display
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            // 圆角背景（保存引用供激活时变色）
            background = roundedDrawable(COLOR_LAYER_NORMAL, dp(8))
            setPadding(0, 0, 0, 0)
            // 按住激活层，松开停用层（松开即回到公共层）
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        mapper?.activateLayer(name)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mapper?.deactivateLayer(name)
                    }
                }
                true
            }
            // 两列均分宽度，高度统一
            val params = LinearLayout.LayoutParams(0, dp(36), 1f)
            params.setMargins(dp(2), dp(2), dp(2), dp(2))
            layoutParams = params
        }
        layerButtons[name] = btn
        return btn
    }

    /**
     * 创建圆角功能按钮（带按下态反馈）
     *
     * @param label 按钮文本
     * @param onClick 点击回调
     * @param weight 水平均分权重（>0 时占满并均分；0 为自适应宽度）
     * @param normalColor 正常态背景色
     * @param pressedColor 按下态背景色
     * @param textSize 文本大小（sp）
     */
    private fun createOverlayButton(
        label: String,
        onClick: () -> Unit,
        weight: Float = 0f,
        normalColor: Int = COLOR_BTN_NORMAL,
        pressedColor: Int = COLOR_BTN_PRESSED,
        textSize: Float = 11f
    ): Button {
        return Button(this).apply {
            text = label
            this.textSize = textSize
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            background = createStateListBackground(normalColor, pressedColor, 8f)
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            )
            if (weight > 0f) params.weight = weight
            params.setMargins(dp(2), dp(2), dp(2), dp(2))
            layoutParams = params
        }
    }

    // ===== 悬浮窗样式辅助 =====

    /** dp → px（接受 Int/Float） */
    private fun dp(value: Number): Int =
        (value.toFloat() * resources.displayMetrics.density).toInt()

    /**
     * 创建圆角矩形背景
     *
     * @param color 填充色
     * @param cornerRadius 圆角半径（px）
     * @param strokeColor 边框色（null=无边框）
     * @param strokeWidth 边框宽度（px）
     */
    private fun roundedDrawable(
        color: Int,
        cornerRadius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            this.cornerRadius = cornerRadius.toFloat()
            if (strokeColor != null) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    /**
     * 创建带按下态反馈的圆角按钮背景
     *
     * @param normalColor 正常态颜色
     * @param pressedColor 按下态颜色
     * @param cornerRadiusDp 圆角半径（dp）
     */
    private fun createStateListBackground(
        normalColor: Int,
        pressedColor: Int,
        cornerRadiusDp: Float
    ): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedDrawable(pressedColor, dp(cornerRadiusDp))
            )
            addState(
                intArrayOf(),
                roundedDrawable(normalColor, dp(cornerRadiusDp))
            )
        }
    }

    private fun updateStatus(text: String) {
        // 缓存最新状态，showExpandedView() 重建 statusText 时使用
        currentStatus = text
        statusText?.post { statusText?.text = text }
        updateNotification(text)
    }

    private fun updateLayerText(layers: List<String>) {
        layerText?.post {
            layerText?.text = if (layers.isEmpty()) "无激活层" else "层: ${layers.joinToString(" > ")}"
        }
    }

    private fun updateLayerButtonColors(activeLayers: List<String>) {
        // 按钮 key 与 activeLayers 都是内部层名，直接比对
        val activeSet = activeLayers.toSet()
        layerButtons.forEach { (name, btn) ->
            btn.post {
                // 通过 GradientDrawable 设置圆角背景颜色（保留圆角）
                val bg = btn.background as? GradientDrawable
                if (name in activeSet) {
                    bg?.setColor(COLOR_LAYER_ACTIVE)   // 绿色=激活
                    btn.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    bg?.setColor(COLOR_LAYER_NORMAL)   // 深灰=未激活
                    btn.setTextColor(0xFFFFFFFF.toInt())
                }
            }
        }
    }

    /**
     * 获取悬浮窗层按钮的 (内部名, 显示名) 列表，与 app 内部配置动态同步
     *
     * - 未重命名的层（name 形如 "LayerN"）→ 显示预设中文名（战斗/骑乘/...）
     * - 用户在设置界面重命名后 → 显示自定义层名
     */
    private fun getLayerDisplayNames(): List<Pair<String, String>> {
        val profile = steamInput?.profile ?: return WoWActionSets.LAYER_NAMES
        return profile.layers.map { layer ->
            val display = if (DEFAULT_LAYER_NAME_REGEX.matches(layer.name)) {
                WoWActionSets.LAYER_NAMES.firstOrNull { it.first == layer.name }?.second ?: layer.name
            } else {
                layer.name
            }
            layer.name to display
        }
    }

    /**
     * 刷新悬浮窗层按钮名称（配置导入/重置后调用）
     *
     * 正常编辑流程（LayerEditActivity 编辑时悬浮窗已移除、退出后重建）会自动同步新层名；
     * 此方法用于服务运行中配置被导入/重置时的即时刷新。
     */
    private fun refreshLayerNames() {
        mainHandler.post {
            if (isOverlayPaused) return@post  // 设置界面期间不重建
            if (isExpanded) {
                showExpandedView()
            } else {
                updateCollapsedViewText(mapper?.getActiveLayers() ?: emptyList())
            }
        }
    }

    // ===== 通知 =====

    private fun createNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "手柄控制器", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SteamLike手柄控制器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止智能暂停监控
        stopSmartMonitor()
        // 移除焦点输入窗口
        gamepadInputView?.let { windowManager?.removeView(it) }
        gamepadInputView = null
        // 停止映射器
        mapper?.stop()
        mapper = null
        steamInput = null
        // 清除 LayerEditActivity 的 SteamInput 引用
        LayerEditActivity.steamInputRef = null
        configManager = null
        // 停止TCP服务器
        bridgeServer?.stop()
        bridgeServer = null
        // 移除悬浮窗UI
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
    }
}
