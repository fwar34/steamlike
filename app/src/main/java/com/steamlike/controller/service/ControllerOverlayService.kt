package com.steamlike.controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.steamlike.controller.LayerEditActivity
import com.steamlike.controller.config.ConfigManager
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
        /** Intent extra: 配置文件 URI */
        const val EXTRA_CONFIG_URI = "config_uri"

        /** 广播: 客户端连接状态变化 */
        const val ACTION_CLIENT_STATUS = "CLIENT_STATUS"
        /** Intent extra: 连接状态文本 */
        const val EXTRA_STATUS_TEXT = "status_text"
        /** Intent extra: 是否已连接 */
        const val EXTRA_CONNECTED = "connected"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var gamepadInputView: GamepadInputView? = null
    private var statusText: TextView? = null
    private var layerText: TextView? = null
    private var hintText: TextView? = null
    private val layerButtons = mutableMapOf<String, Button>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 悬浮窗收起/展开状态
    private var isExpanded = false
    private var overlayParams: WindowManager.LayoutParams? = null

    private var steamInput: SteamInput? = null
    private var mapper: KeyboardMouseMapper? = null
    private var bridgeServer: InputBridgeServer? = null
    private var configManager: ConfigManager? = null

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
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
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
        }
        // 首次启动时初始化映射器
        if (steamInput == null) {
            startMapper()
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
                bridgeServer = InputBridgeServer()
                bridgeServer?.onClientConnected = { addr ->
                    Log.i(TAG, "Client connected: $addr")
                    updateStatus("Client connected: $addr")
                    broadcastClientStatus("Client connected: $addr", true)
                }
                bridgeServer?.onClientDisconnected = { addr ->
                    Log.i(TAG, "Client disconnected: $addr")
                    val msg = "Waiting for Windows client... (port ${InputBridgeServer.DEFAULT_PORT})"
                    updateStatus(msg)
                    broadcastClientStatus(msg, false)
                }
                bridgeServer?.onServerError = { msg ->
                    Log.e(TAG, "Server error: $msg")
                    updateStatus("Server error: $msg")
                    broadcastClientStatus("Server error: $msg", false)
                }

                if (bridgeServer?.start() != true) {
                    Log.e(TAG, "TCP server start failed")
                    updateStatus("TCP server start failed")
                    broadcastClientStatus("TCP server start failed", false)
                    return@Thread
                }
                Log.i(TAG, "TCP server started on port ${InputBridgeServer.DEFAULT_PORT}")

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
                }

                if (mapper?.start() == true) {
                    Log.i(TAG, "Mapper started successfully")
                    // 加载用户配置（覆盖默认WoW预设的绑定和属性）
                    // 动作定义和回调保持不变，仅修改绑定关系
                    loadUserConfig()

                    // 创建焦点输入窗口（addView 必须在主线程执行）
                    mainHandler.post { createGamepadInputWindow() }

                    val waitMsg = "Waiting for Windows client... (port ${InputBridgeServer.DEFAULT_PORT})"
                    updateStatus(waitMsg)
                    broadcastClientStatus(waitMsg, false)
                    updateLayerText(mapper?.getActiveLayers() ?: emptyList())
                    updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())
                } else {
                    Log.e(TAG, "Mapper start failed - check overlay permission")
                    updateStatus("Start failed - check overlay permission")
                    broadcastClientStatus("Start failed - check overlay permission", false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startMapper error", e)
                updateStatus("Error: ${e.message}")
                broadcastClientStatus("Error: ${e.message}", false)
            }
        }.start()
    }

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
            val success = cm.loadFromUri(uri)
            if (success) {
                toast("配置已导入")
            } else {
                toast("导入失败: 无法读取配置文件")
                return
            }

            // 更新悬浮窗的层显示（导入可能修改了层定义）
            updateLayerText(mapper?.getActiveLayers() ?: emptyList())
            updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())
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

    // ===== 焦点输入窗口 =====

    /**
     * 创建全屏透明焦点窗口捕获手柄事件
     *
     * - FLAG_NOT_TOUCHABLE: 触摸穿透到Winlator
     * - 可获焦点: 接收手柄KeyEvent和MotionEvent
     * - 全屏透明: 不遮挡画面
     */
    private fun createGamepadInputWindow() {
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

        // 请求焦点以接收手柄按键事件
        gamepadInputView?.post {
            gamepadInputView?.requestFocus()
        }
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
        showCollapsedView()
    }

    /**
     * 显示收起状态的悬浮窗
     *
     * 移除展开视图（如果存在），创建一个小图标按钮。
     * 点击小图标切换到展开状态。
     */
    private fun showCollapsedView() {
        // 移除展开视图
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null

        // 创建收起视图（小图标按钮）
        val collapsed = TextView(this).apply {
            text = "🎮"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x88000000.toInt())
            setPadding(20, 8, 20, 8)
        }
        setupOverlayTouchListener(collapsed, isCollapsed = true)
        overlayView = collapsed
        overlayParams?.let { windowManager?.addView(collapsed, it) }
        isExpanded = false
    }

    /**
     * 显示展开状态的悬浮窗
     *
     * 移除收起视图，创建完整的操作层面板。
     * 面板包含"收起"按钮可切换回收起状态。
     */
    private fun showExpandedView() {
        // 移除收起视图
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 16, 24, 16)
        }

        // 状态
        statusText = TextView(this).apply {
            text = "初始化中..."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        container.addView(statusText)

        // 操作层堆栈
        layerText = TextView(this).apply {
            text = ""
            setTextColor(0xFFAAAAFF.toInt())
            textSize = 10f
            setPadding(0, 4, 0, 0)
        }
        container.addView(layerText)

        // 10个操作层按钮 (2列 x 5行)
        val layers = WoWActionSets.LAYER_NAMES
        layers.chunked(2).forEach { rowLayers ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 0)
            }
            rowLayers.forEach { (name, display) ->
                row.addView(createLayerButton(name, display))
            }
            container.addView(row)
        }

        // 清除/收起/关闭行
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 4)
        }
        ctrlRow.addView(createOverlayButton("清除层") { mapper?.clearAllLayers() })
        ctrlRow.addView(createOverlayButton("收起") { showCollapsedView() })
        ctrlRow.addView(createOverlayButton("关闭") { stopSelf() })
        container.addView(ctrlRow)

        // 快捷键提示
        hintText = TextView(this).apply {
            text = "LB+方向键/A/B/X/Y/L3/R3 切层\nLB+HOME 清全部\n组合键: A+RB=选怪 D-Pad+L3=栏5-8 D-Pad+R3=栏9/0/-/="
            setTextColor(0xFF888888.toInt())
            textSize = 9f
            setPadding(0, 4, 0, 0)
        }
        container.addView(hintText)

        setupOverlayTouchListener(container, isCollapsed = false)
        overlayView = container
        overlayParams?.let { windowManager?.addView(container, it) }
        isExpanded = true

        // 刷新当前状态（展开后显示最新状态）
        if (mapper != null) {
            updateLayerText(mapper?.getActiveLayers() ?: emptyList())
            updateLayerButtonColors(mapper?.getActiveLayers() ?: emptyList())
        }
    }

    /**
     * 为悬浮窗视图设置拖动和点击监听
     *
     * - 拖动: 移动悬浮窗位置（收起和展开状态都支持）
     * - 点击: 收起状态下点击触发展开；展开状态下不拦截按钮点击
     *
     * @param view 目标视图
     * @param isCollapsed 是否为收起状态（收起状态下点击=展开）
     */
    private fun setupOverlayTouchListener(view: View, isCollapsed: Boolean) {
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
                    if (!hasMoved && isCollapsed) {
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
            textSize = 9f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x44000000.toInt())
            setPadding(16, 4, 16, 4)
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
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(4, 0, 4, 0)
            layoutParams = params
        }
        layerButtons[name] = btn
        return btn
    }

    private fun createOverlayButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 9f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x44000000.toInt())
            setPadding(16, 4, 16, 4)
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(4, 0, 4, 0)
            layoutParams = params
        }
    }

    private fun updateStatus(text: String) {
        statusText?.post { statusText?.text = text }
        updateNotification(text)
    }

    private fun updateLayerText(layers: List<String>) {
        layerText?.post {
            layerText?.text = if (layers.isEmpty()) "无激活层" else "层: ${layers.joinToString(" > ")}"
        }
    }

    private fun updateLayerButtonColors(activeLayers: List<String>) {
        val activeDisplayNames = activeLayers.toSet()
        layerButtons.forEach { (name, btn) ->
            btn.post {
                val display = WoWActionSets.LAYER_NAMES.firstOrNull { it.first == name }?.second ?: name
                if (display in activeDisplayNames) {
                    btn.setBackgroundColor(0xFF4CAF50.toInt())  // 绿色=激活
                    btn.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    btn.setBackgroundColor(0x44000000.toInt())  // 半透明=未激活
                    btn.setTextColor(0xFFFFFFFF.toInt())
                }
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
