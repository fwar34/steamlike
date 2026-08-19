package com.steamlike.controller

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.steamlike.controller.config.AppConfig
import com.steamlike.controller.config.AppConfigStore
import com.steamlike.controller.config.ConfigManager
import com.steamlike.controller.config.ControllerConfig
import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerProfile
import com.steamlike.controller.core.GlobalSettings
import com.steamlike.controller.service.ControllerOverlayService
import com.steamlike.controller.ui.UiKit
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var overlayButton: Button
    private lateinit var configStatusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var usageTextView: TextView
    /** 智能暂停开关 */
    private lateinit var smartPauseSwitch: Switch
    /** 捕获白名单输入框 */
    private lateinit var whitelistEditText: EditText
    /** 使用情况访问授权入口按钮 */
    private lateinit var usageStatsButton: Button
    /** 使用情况访问授权状态文本 */
    private lateinit var usageStatsStatusText: TextView
    /** 手柄捕获开关（与悬浮窗暂停/恢复双向同步） */
    private lateinit var captureSwitch: Switch
    /** 捕获状态显示文本 */
    private lateinit var captureStatusText: TextView
    /** 悬浮窗"游戏"按钮拉起应用包名输入框 */
    private lateinit var launcherEditText: EditText
    /** 抑制捕获开关监听标志（程序化同步状态时避免循环触发） */
    private var suppressCaptureListener = false
    /** TCP监听地址输入框 */
    private lateinit var hostEditText: EditText
    /** TCP监听端口输入框 */
    private lateinit var portEditText: EditText
    /** 主界面滚动容器（用于保存/恢复滚动位置） */
    private lateinit var mainScroll: ScrollView

    // ====================================================================
    // SAF（Storage Access Framework）文件选择器
    // ====================================================================
    // 使用 AndroidX Activity Result API 注册文件选择器，
    // 替代已废弃的 startActivityForResult。
    //
    // 导出: CreateDocument → 用户选择保存位置 → 获取 content:// URI
    // 导入: OpenDocument   → 用户选择配置文件 → 获取 content:// URI
    //
    // 获取 URI 后，通过 Intent 将操作转发给 ControllerOverlayService 执行。
    // ====================================================================

    /**
     * SAF 创建文档启动器（用于导出配置）
     *
     * 点击"导出配置"按钮时调用 [launch]，弹出系统文件选择器让用户选择保存位置。
     * 用户选择后，回调中获取 content:// URI，发送导出 Intent 给服务。
     *
     * MIME 类型设为 "application/json"，文件名建议为 "steamlike_config.json"。
     */
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            // 用户已选择保存位置，发送导出请求给服务
            sendConfigIntent(ControllerOverlayService.ACTION_EXPORT_CONFIG, uri)
        }
    }

    /**
     * SAF 打开文档启动器（用于导入配置）
     *
     * 点击"导入配置"按钮时调用 [launch]，弹出系统文件选择器让用户选择配置文件。
     * 用户选择后，回调中获取 content:// URI，发送导入 Intent 给服务。
     *
     * 支持的 MIME 类型: application/json, text/plain, 以及通配符（兼容性）
     */
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 用户已选择配置文件，发送导入请求给服务
            sendConfigIntent(ControllerOverlayService.ACTION_IMPORT_CONFIG, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 加载当前运行时配置（随配置文件持久化）
        val appCfg = AppConfigStore.load(this)

        val scroll = ScrollView(this).apply {
            UiKit.applyDarkBackground(this, this@MainActivity)
        }
        mainScroll = scroll
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 24),
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 32)
            )
        }

        // ===== 头部 =====
        container.addView(UiKit.bigTitle(this, "SteamLike 手柄控制器"))
        container.addView(UiKit.caption(
            this,
            "WoW 乌龟服 1.18.1 · 手柄 → 键鼠桥接（Winlator）",
            0xFF888888.toInt(), 13f
        ))
        container.addView(UiKit.spacer(this, 8))

        // ===== 运行状态卡片 =====
        val statusCard = UiKit.card(this)
        statusCard.addView(UiKit.sectionTitle(this, "运行状态"))
        statusCard.addView(UiKit.spacer(this, 6))
        statusText = UiKit.caption(this, "", 0xFFDDDDDD.toInt(), 13f)
        statusCard.addView(statusText)
        statusCard.addView(UiKit.spacer(this, 4))
        // 连接状态：固定两行高度，服务广播更新文本时不改变卡片高度，避免页面自动滚动
        connectionStatusText = UiKit.caption(this, "Client: not started", 0xFFAAAAAA.toInt(), 13f).apply {
            minLines = 2
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusCard.addView(connectionStatusText)
        statusCard.addView(UiKit.spacer(this, 10))
        // 悬浮窗权限按钮
        overlayButton = UiKit.button(this, "授予悬浮窗权限", {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }, UiKit.Style.PRIMARY)
        statusCard.addView(overlayButton)
        container.addView(statusCard)

        // ===== 智能暂停卡片 =====
        val smartCard = UiKit.card(this)
        smartCard.addView(UiKit.sectionTitle(this, "智能暂停（修复右滑返回失效）"))
        smartCard.addView(UiKit.spacer(this, 4))
        smartCard.addView(UiKit.caption(
            this,
            ("焦点窗口会拦截 Android 13+ 的右滑返回手势。白名单应用（如 Winlator）在前台时保持手柄捕获，\n"
                + "切到其他应用自动暂停捕获，右滑返回恢复正常。需要授权\"使用情况访问\"，\n"
                + "以上设置随配置文件一并保存/导出。"),
            0xFF999999.toInt(), 11f
        ))
        smartCard.addView(UiKit.spacer(this, 6))

        // 智能暂停开关
        smartPauseSwitch = UiKit.switchRow(this, "启用智能暂停", appCfg.smartPauseEnabled) { checked ->
            updateUsageStatsStatus()
            toastLog(if (checked) "智能暂停已开启（切出游戏自动暂停捕获）" else "智能暂停已关闭（手动模式）")
        }
        smartCard.addView(smartPauseSwitch)

        // 捕获开关（与悬浮窗暂停/恢复按钮双向同步）
        captureSwitch = UiKit.switchRow(this, "手柄捕获", appCfg.captureEnabled) { checked ->
            if (!suppressCaptureListener) setCaptureSwitch(checked)
        }
        smartCard.addView(captureSwitch)

        // 捕获状态显示（实时接收服务广播同步）
        captureStatusText = UiKit.caption(this, "", 0xFF999999.toInt(), 11f)
        smartCard.addView(captureStatusText)
        smartCard.addView(UiKit.spacer(this, 6))

        // 白名单输入框（支持多个包名，逗号分隔）
        smartCard.addView(UiKit.label(this, "捕获白名单（支持多个包名，逗号分隔）"))
        whitelistEditText = UiKit.input(
            this,
            "如: com.winlator, com.winlator.hub",
            appCfg.captureWhitelist.joinToString(",")
        )
        smartCard.addView(whitelistEditText)
        smartCard.addView(UiKit.spacer(this, 6))

        // 拉起应用包名（悬浮窗"游戏"按钮使用）
        smartCard.addView(UiKit.label(this, "悬浮窗\"游戏\"按钮拉起的应用包名"))
        launcherEditText = UiKit.input(this, "如: com.winlator", appCfg.launcherPackage)
        smartCard.addView(launcherEditText)
        smartCard.addView(UiKit.spacer(this, 8))

        // 使用情况访问授权入口
        usageStatsButton = UiKit.button(this, "授权使用情况访问", {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        smartCard.addView(usageStatsButton)
        usageStatsStatusText = UiKit.caption(this, "", 0xFF999999.toInt(), 11f)
        smartCard.addView(usageStatsStatusText)
        container.addView(smartCard)

        // ===== TCP 服务器配置卡片 =====
        val serverCard = UiKit.card(this)
        serverCard.addView(UiKit.sectionTitle(this, "TCP 服务器配置"))
        serverCard.addView(UiKit.spacer(this, 6))

        // 地址和端口输入框（水平布局）
        val hostPortLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        hostEditText = UiKit.input(this, "监听地址 (如 0.0.0.0)", appCfg.serverHost).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        portEditText = UiKit.input(this, "端口", appCfg.serverPort.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        hostPortLayout.addView(hostEditText)
        hostPortLayout.addView(portEditText)
        serverCard.addView(hostPortLayout)
        serverCard.addView(UiKit.spacer(this, 8))

        // 启动服务按钮
        startButton = UiKit.button(this, "启动手柄映射", {
            val host = hostEditText.text.toString().trim()
            val portStr = portEditText.text.toString().trim()
            val port = portStr.toIntOrNull() ?: 27015
            // 保存全部运行时配置到配置文件（随配置一并导入/导出）
            saveRuntimeConfig()
            val intent = Intent(this@MainActivity, ControllerOverlayService::class.java).apply {
                putExtra(ControllerOverlayService.EXTRA_HOST, host)
                putExtra(ControllerOverlayService.EXTRA_PORT, port)
                putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, smartPauseSwitch.isChecked)
                putExtra(ControllerOverlayService.EXTRA_WHITELIST, whitelistEditText.text.toString().trim())
                putExtra(ControllerOverlayService.EXTRA_LAUNCHER_PACKAGE, launcherEditText.text.toString().trim())
            }
            ContextCompat.startForegroundService(this@MainActivity, intent)
            // 连接状态由服务广播(ACTION_CLIENT_STATUS)实时更新，不在此手动改文本（避免布局高度变化导致滚动）
            toastLog("✅ 服务已启动！监听 ${host.ifBlank { "0.0.0.0" }}:$port")
            // 收起软键盘并清除输入框焦点，避免布局因 IME 弹出/收起而自动滚动
            hostEditText.clearFocus()
            portEditText.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(this@MainActivity.currentFocus?.windowToken, 0)
            logD("Start button clicked, host=$host port=$port")
        }, UiKit.Style.PRIMARY)
        serverCard.addView(startButton)
        serverCard.addView(UiKit.spacer(this, 6))

        // 停止按钮
        serverCard.addView(UiKit.button(this, "停止服务", {
            val intent = Intent(this@MainActivity, ControllerOverlayService::class.java)
            intent.action = ControllerOverlayService.ACTION_STOP
            startService(intent)
            stopService(Intent(this@MainActivity, ControllerOverlayService::class.java))
            updateUI()
        }, UiKit.Style.DANGER))
        container.addView(serverCard)

        // ===== 配置管理卡片 =====
        val configCard = UiKit.card(this)
        configCard.addView(UiKit.sectionTitle(this, "配置管理"))
        configCard.addView(UiKit.spacer(this, 4))

        // 配置状态
        configStatusText = UiKit.caption(this, "", 0xFFDDDDDD.toInt(), 12f)
        configCard.addView(configStatusText)
        configCard.addView(UiKit.spacer(this, 6))

        // 导出配置按钮
        configCard.addView(UiKit.button(this, "导出配置", {
            // 先确保服务已启动
            ensureServiceRunning()
            // 启动 SAF 创建文档
            createDocumentLauncher.launch("steamlike_config.json")
        }))
        configCard.addView(UiKit.spacer(this, 6))

        // 导入配置按钮
        configCard.addView(UiKit.button(this, "导入配置", {
            // 先确保服务已启动
            ensureServiceRunning()
            // 启动 SAF 打开文档
            openDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }))
        configCard.addView(UiKit.spacer(this, 6))

        // 重置为默认按钮
        configCard.addView(UiKit.button(this, "重置为默认配置", {
            ensureServiceRunning()
            val intent = Intent(this@MainActivity, ControllerOverlayService::class.java)
            intent.action = ControllerOverlayService.ACTION_RESET_CONFIG
            ContextCompat.startForegroundService(this@MainActivity, intent)
            toastLog("正在重置...")
            // 延迟刷新UI
            configStatusText.postDelayed({ updateConfigStatus() }, 1000)
        }, UiKit.Style.DANGER))
        configCard.addView(UiKit.spacer(this, 6))

        // 操作层设置按钮（不启动手柄映射服务；LayerEditActivity 直接读写配置文件）
        configCard.addView(UiKit.button(
            this,
            "操作层设置",
            onClick = {
                startActivity(Intent(this@MainActivity, LayerEditActivity::class.java))
            },
            style = UiKit.Style.PRIMARY
        ))
        container.addView(configCard)

        // ===== 右摇杆优化设置卡片 =====
        val lookCard = UiKit.card(this)
        lookCard.addView(UiKit.sectionTitle(this, "右摇杆优化设置"))
        lookCard.addView(UiKit.spacer(this, 4))
        lookCard.addView(UiKit.caption(
            this,
            ("右摇杆控制鼠标视角时，可通过以下参数调节手感。\n"
                + "• 觉得滑动过快 → 降低「灵敏度」或提高「加速曲线指数」\n"
                + "• 觉得不够流畅/抖动 → 提高「平滑系数」(0.5~0.8 推荐)\n"
                + "• 摇杆居中时仍在漂移 → 提高「死区」(0.1~0.25)\n"
                + "• 轻推难以精确瞄准 → 降低「加速曲线指数」靠近 1.0"),
            0xFF999999.toInt(), 11f
        ))
        lookCard.addView(UiKit.spacer(this, 4))

        // 读取当前设置值
        val currentSettings = getCurrentProfile().globalSettings

        // 死区
        val deadzoneEdit = makeSettingsEdit(
            title = "死区 (Deadzone)",
            hint = "0.0 ~ 1.0，默认 0.15",
            desc = ("小于此值的摇杆输入会被视为零输入，消除摇杆中心漂移。\n"
                + "推荐: 0.10~0.25。摇杆容易漂移可调高；摇杆精准可调低。"),
            value = currentSettings.deadzone
        )
        lookCard.addView(deadzoneEdit.first)

        // 视角灵敏度
        val sensitivityEdit = makeSettingsEdit(
            title = "右摇杆视角灵敏度 (Look Sensitivity)",
            hint = "0.1 ~ 5.0，默认 0.5",
            desc = ("右摇杆控制视角/鼠标移动的整体速度倍率。\n"
                + "数值越大移动越快。觉得滑动过快请降低此值(如 0.3)；过慢可提高(如 0.8)。"),
            value = currentSettings.lookSensitivity
        )
        lookCard.addView(sensitivityEdit.first)

        // 平滑系数
        val smoothingEdit = makeSettingsEdit(
            title = "视角平滑系数 (Look Smoothing)",
            hint = "0.0 ~ 0.95，默认 0.5",
            desc = ("指数移动平均(EMA)滤波系数，降低摇杆抖动让移动更顺滑。\n"
                + "0=关闭平滑(最跟手但有抖动)，越大越顺滑但延迟增加。\n"
                + "推荐: 0.3~0.7。觉卡顿降一点，觉延迟降一点。"),
            value = currentSettings.lookSmoothing
        )
        lookCard.addView(smoothingEdit.first)

        // 加速曲线指数
        val accelerationEdit = makeSettingsEdit(
            title = "视角加速曲线指数 (Look Acceleration)",
            hint = "0.5 ~ 3.0，默认 1.5",
            desc = ("非线性响应曲线。1.0=线性(轻推重推一致)；\n"
                + ">1.0 轻推更慢、重推更快(利于精确瞄准又保证转向速度)；\n"
                + "<1.0 轻推更快、重推相对变慢。\n"
                + "觉得轻推过快难以瞄准 → 提高此值(如 1.8~2.2)。"),
            value = currentSettings.lookAcceleration
        )
        lookCard.addView(accelerationEdit.first)

        // 光标速度倍率
        val cursorSpeedEdit = makeSettingsEdit(
            title = "光标移动速度倍率 (Cursor Speed)",
            hint = "默认 1.0",
            desc = ("左摇杆/其他鼠标移动场景的速度倍率(右摇杆视角由灵敏度控制)。\n"
                + ">1.0 更快，<1.0 更慢。一般保持 1.0 即可。"),
            value = currentSettings.cursorSpeed
        )
        lookCard.addView(cursorSpeedEdit.first)
        lookCard.addView(UiKit.spacer(this, 6))

        // 保存按钮
        lookCard.addView(UiKit.button(
            this,
            "保存右摇杆设置",
            onClick = {
                val dz = deadzoneEdit.second.text.toString().trim().toFloatOrNull()
                val sens = sensitivityEdit.second.text.toString().trim().toFloatOrNull()
                val sm = smoothingEdit.second.text.toString().trim().toFloatOrNull()
                val ac = accelerationEdit.second.text.toString().trim().toFloatOrNull()
                val cs = cursorSpeedEdit.second.text.toString().trim().toFloatOrNull()
                if (dz == null || sens == null || sm == null || ac == null || cs == null) {
                    toastLog("请填写全部 5 个数值", long = true)
                } else if (!Settings.canDrawOverlays(this@MainActivity)) {
                    toastLog("请先授予悬浮窗权限并启动服务", long = true)
                } else {
                    ensureServiceRunning()
                    val intent = Intent(this@MainActivity, ControllerOverlayService::class.java).apply {
                        action = ControllerOverlayService.ACTION_UPDATE_SETTINGS
                        putExtra(ControllerOverlayService.EXTRA_DEADZONE, dz)
                        putExtra(ControllerOverlayService.EXTRA_LOOK_SENSITIVITY, sens)
                        putExtra(ControllerOverlayService.EXTRA_LOOK_SMOOTHING, sm)
                        putExtra(ControllerOverlayService.EXTRA_LOOK_ACCELERATION, ac)
                        putExtra(ControllerOverlayService.EXTRA_CURSOR_SPEED, cs)
                    }
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                    toastLog("正在保存右摇杆设置...")
                }
            },
            style = UiKit.Style.PRIMARY
        ))
        container.addView(lookCard)

        // ===== Windows 客户端卡片 =====
        val winCard = UiKit.card(this)
        winCard.addView(UiKit.sectionTitle(this, "Windows 客户端"))
        winCard.addView(UiKit.spacer(this, 6))
        winCard.addView(UiKit.button(this, "导出 Windows 客户端到 Download/AControler", {
            exportFilesToDownload()
        }))
        container.addView(winCard)

        // ===== 调试卡片 =====
        val debugCard = UiKit.card(this)
        debugCard.addView(UiKit.sectionTitle(this, "调试"))
        debugCard.addView(UiKit.spacer(this, 6))
        debugCard.addView(UiKit.button(
            this,
            "测试手柄按键（模拟器调试用）",
            onClick = {
                ensureServiceRunning()
                if (LayerEditActivity.steamInputRef == null) {
                    toastLog("服务正在初始化，请稍候...")
                    var waited = 0
                    val tick = 100
                    val maxWait = 3000
                    configStatusText.postDelayed(object : Runnable {
                        override fun run() {
                            waited += tick
                            if (LayerEditActivity.steamInputRef != null) {
                                startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java))
                            } else if (waited < maxWait) {
                                configStatusText.postDelayed(this, tick.toLong())
                            } else {
                                toastLog("服务初始化超时，请重试", long = true)
                            }
                        }
                    }, tick.toLong())
                } else {
                    startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java))
                }
            }
        ))
        container.addView(debugCard)

        // 使用说明（动态根据当前 profile 生成操作层切换说明）
        container.addView(UiKit.spacer(this, 10))
        usageTextView = UiKit.caption(this, "", 0xFF888888.toInt(), 11f)
        container.addView(usageTextView)

        scroll.addView(container)
        setContentView(scroll)

        // 恢复上次退出的滚动位置（上划退出/进程重建后回到原位置）
        mainScroll.post {
            mainScroll.scrollTo(
                0, getSharedPreferences(SCROLL_PREFS, MODE_PRIVATE).getInt(KEY_SCROLL_Y, 0)
            )
        }
    }

    // ====================================================================
    // 配置管理辅助方法
    // ====================================================================
    // 这些方法在 Activity 中处理 UI 交互，实际的配置操作由 Service 执行。
    // Activity → Intent → Service 的通信模式确保配置操作在服务生命周期内执行。
    // ====================================================================

    /**
     * 确保映射服务已启动
     *
     * 导出/导入/重置操作需要服务运行中才能执行（服务持有 SteamInput 实例）。
     * 如果服务未启动，此方法会自动启动服务。
     *
     * ## 前置条件
     * - 必须已授予悬浮窗权限（SYSTEM_ALERT_WINDOW）
     * - 未授予权限时显示提示，不启动服务
     *
     * ## 行为
     * - 服务已运行: 无操作（startForegroundService 不会重复创建）
     * - 服务未运行: 启动前台服务
     */
    private fun ensureServiceRunning() {
        if (!Settings.canDrawOverlays(this)) {
            toastLog("请先授予悬浮窗权限", long = true)
            return
        }
        // 启动前台服务（如果已启动则不会重复启动，onStartCommand 会再次调用）
        // 保存全部运行时配置到配置文件，并通过 EXTRA 传给服务
        saveRuntimeConfig()
        val cfg = AppConfigStore.load(this)
        val intent = Intent(this, ControllerOverlayService::class.java).apply {
            putExtra(ControllerOverlayService.EXTRA_HOST, cfg.serverHost)
            putExtra(ControllerOverlayService.EXTRA_PORT, cfg.serverPort)
            putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, smartPauseSwitch.isChecked)
            putExtra(ControllerOverlayService.EXTRA_WHITELIST, whitelistEditText.text.toString().trim())
            putExtra(ControllerOverlayService.EXTRA_LAUNCHER_PACKAGE, launcherEditText.text.toString().trim())
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * 发送配置操作 Intent 到服务
     *
     * 将用户通过 SAF 选择的文件 URI 和操作类型通过 Intent 发送给服务。
     * 服务在 [ControllerOverlayService.onStartCommand] 中接收并执行实际操作。
     *
     * ## 通信流程
     * ```
     * Activity (SAF 回调)
     *      ↓ 构建 Intent (action=EXPORT/IMPORT, extra=URI)
     *      ↓ startForegroundService(intent)
     * Service.onStartCommand()
     *      ↓ 根据 action 分发到 handleExportConfig/handleImportConfig
     *      ↓ 执行配置操作
     *      ↓ Toast 提示结果
     * ```
     *
     * @param action 操作类型:
     *        - [ControllerOverlayService.ACTION_EXPORT_CONFIG] 导出
     *        - [ControllerOverlayService.ACTION_IMPORT_CONFIG] 导入
     * @param uri 用户通过 SAF 选择的文件 URI
     */
    private fun sendConfigIntent(action: String, uri: Uri) {
        if (!Settings.canDrawOverlays(this)) {
            toastLog("请先授予悬浮窗权限并启动服务", long = true)
            return
        }
        val intent = Intent(this, ControllerOverlayService::class.java).apply {
            this.action = action
            putExtra(ControllerOverlayService.EXTRA_CONFIG_URI, uri)
        }
        ContextCompat.startForegroundService(this, intent)
        // 显示操作进行中提示
        toastLog(
            if (action == ControllerOverlayService.ACTION_EXPORT_CONFIG) "正在导出..." else "正在导入..."
        )
        // 延迟刷新配置状态显示（等待服务完成操作）
        configStatusText.postDelayed({ updateConfigStatus() }, 1000)
    }

    /**
     * 更新配置状态显示
     *
     * 检查内部配置文件是否存在，在 UI 上显示配置状态:
     * - 已加载: 显示文件名和大小
     * - 未加载: 提示使用默认 WoW 预设
     *
     * 在 [onResume] 和配置操作完成后调用。
     */
    private fun updateConfigStatus() {
        // 配置文件路径: {filesDir}/steamlike_config.json
        val configFile = File(filesDir, "steamlike_config.json")
        val hasConfig = configFile.exists()
        configStatusText.text = if (hasConfig) {
            "配置文件: 已加载\n路径: ${configFile.name}\n大小: ${configFile.length()} 字节"
        } else {
            "配置文件: 未加载（使用默认 WoW 预设）"
        }
    }

    /**
     * 保存全部运行时配置到配置文件（随配置一并导入/导出）
     *
     * 包含：服务器地址/端口、智能暂停开关、捕获白名单（多个）、
     * 捕获开关、悬浮窗"游戏"按钮拉起应用包名。
     */
    private fun saveRuntimeConfig() {
        val cfg = AppConfig(
            serverHost = hostEditText.text.toString().trim().ifBlank { AppConfig.DEFAULT_HOST },
            serverPort = portEditText.text.toString().trim().toIntOrNull() ?: AppConfig.DEFAULT_PORT,
            smartPauseEnabled = smartPauseSwitch.isChecked,
            captureWhitelist = AppConfig.parseWhitelist(whitelistEditText.text.toString()),
            captureEnabled = captureSwitch.isChecked,
            launcherPackage = launcherEditText.text.toString().trim().ifBlank { AppConfig.DEFAULT_LAUNCHER }
        )
        AppConfigStore.save(this, cfg)
    }

    /**
     * 捕获开关变化 → 持久化 + 通知服务（悬浮窗同步）
     *
     * @param enabled true=恢复捕获, false=暂停捕获
     */
    private fun setCaptureSwitch(enabled: Boolean) {
        saveRuntimeConfig()
        if (isServiceRunning()) {
            val intent = Intent(this, ControllerOverlayService::class.java).apply {
                action = ControllerOverlayService.ACTION_SET_CAPTURE
                putExtra(ControllerOverlayService.EXTRA_CAPTURE_ENABLED, enabled)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            toastLog("服务未运行，开关状态已保存（启动手柄映射后生效）")
        }
        logD("Capture switch set to $enabled")
    }

    /**
     * 检查 ControllerOverlayService 是否在运行
     */
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(100)
        return services.any {
            it.service.className == ControllerOverlayService::class.java.name &&
                it.service.packageName == packageName
        }
    }

    /**
     * 从配置文件同步捕获开关状态（onResume 时调用）
     */
    private fun syncCaptureSwitchFromPrefs() {
        if (!::captureSwitch.isInitialized) return
        val enabled = AppConfigStore.load(this).captureEnabled
        suppressCaptureListener = true
        captureSwitch.isChecked = enabled
        suppressCaptureListener = false
        captureStatusText.text = if (enabled) "捕获状态: ✅ 运行中" else "捕获状态: ⏸ 已暂停（可点悬浮窗恢复）"
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
     * 刷新"使用情况访问"授权状态显示
     */
    private fun updateUsageStatsStatus() {
        if (!::usageStatsStatusText.isInitialized) return
        val granted = hasUsageStatsPermission()
        usageStatsStatusText.text = if (granted) {
            "使用情况访问: ✅ 已授权（智能暂停可用）"
        } else {
            if (smartPauseSwitch.isChecked) {
                "使用情况访问: ❌ 未授权 — 点击上方按钮前往系统设置开启，否则智能暂停不生效（手动模式）"
            } else {
                "使用情况访问: ❌ 未授权（智能暂停已关闭，不影响手动模式）"
            }
        }
    }

    /**
     * 更新使用说明文本
     *
     * 操作层切换说明根据当前 profile 的 triggerButton 动态生成。
     * 使用逐行拼接（而非 trimIndent），避免内嵌多行变量破坏缩进对齐。
     */
    private fun updateUsageText() {
        // 动态生成操作层切换说明
        val layerSwitchLines = buildLayerSwitchLines()

        usageTextView.text = buildString {
            append("📖 使用说明\n\n")
            append("【第一步】Android 端\n")
            append("1. 授予悬浮窗权限\n")
            append("2. 点击「启动手柄映射」\n")
            append("3. 切到 Winlator 运行 WoW\n\n")
            append("【第二步】Windows 端\n")
            append("1. 点「导出 Windows 客户端」，从 Download/AControler\n")
            append("   取出 inputbridge_client.exe 与 control.bat\n")
            append("2. 复制到 Winlator 的 C 盘\n")
            append("3. 运行 control.bat start（或直接运行 exe）\n")
            append("4. 保持窗口打开，切回游戏\n\n")
            append("架构：Android（焦点窗口 + TCP:27015）\n")
            append("      ←→ Windows（SendInput 注入）\n\n")
            append("【操作层切换】（按住触发键激活，松开回公共层）\n")
            append(layerSwitchLines)
            append("\n\n")
            append("【配置管理】\n")
            append("· 操作层设置：可视化编辑按键映射 / 层名 / 触发键\n")
            append("· 导出 / 导入：JSON 完整备份（含运行时设置）\n")
            append("· 重置配置：恢复默认 WoW 预设\n\n")
            append("【悬浮窗】\n")
            append("· 收起胶囊显示当前层，点击展开面板，可拖动\n")
            append("· 「游戏」拉起 Winlator；「暂停/恢复」切换捕获\n")
            append("· 层按钮按住激活、松开回公共层；「清除层」清空激活层\n")
            append("· 「关闭」停止服务\n")
        }
    }

    /**
     * 获取当前生效的 ControllerProfile
     *
     * 数据源优先级:
     * 1. 服务已启动: 读取运行时 [LayerEditActivity.steamInputRef] 的 profile
     * 2. 服务未启动但配置文件存在: 从内部配置文件加载
     * 3. 都没有: 使用代码内置默认 profile
     */
    private fun getCurrentProfile(): ControllerProfile {
        LayerEditActivity.steamInputRef?.profile?.let { return it }
        val configFile = File(filesDir, "steamlike_config.json")
        if (configFile.exists()) {
            return try {
                ControllerConfig.fromJson(configFile.readText())
            } catch (e: Exception) {
                ControllerProfile.createDefault()
            }
        }
        return ControllerProfile.createDefault()
    }

    /**
     * 创建一个设置项 UI 区块（标题 + 说明 + 输入框）
     *
     * @param title 设置项名称
     * @param hint 输入框提示
     * @param desc 设置项说明文本（多行）
     * @param value 当前值（Float）
     * @return Pair(LinearLayout 视图, EditText 输入框)
     */
    private fun makeSettingsEdit(
        title: String,
        hint: String,
        desc: String,
        value: Float
    ): Pair<LinearLayout, EditText> {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, UiKit.dp(this@MainActivity, 10), 0, 0)
        }
        layout.addView(UiKit.label(this, title))
        layout.addView(UiKit.caption(this, desc, 0xFF999999.toInt(), 10f))
        layout.addView(UiKit.spacer(this, 4))
        val edit = UiKit.input(this, hint, value.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(edit)
        return Pair(layout, edit)
    }

    /**
     * 根据当前 profile 的 layers.triggerButton 动态构建操作层切换说明
     *
     * 已设置 triggerButton 的层显示为 "按住 <按键名> → 激活 <层名>"
     * 未设置 triggerButton 的层显示为 "<层名>: 未设置触发键"
     *
     * @return 多行字符串，每行一个操作层
     */
    private fun buildLayerSwitchLines(): String {
        val profile = getCurrentProfile()
        // 先计算所有已设置触发键的最大显示宽度，确保 → 箭头对齐
        val triggerNames = profile.layers.mapNotNull { it.triggerButton?.let { b -> buttonDisplayName(b) } }
        val maxDisplayWidth = if (triggerNames.isEmpty()) 0 else triggerNames.maxOf { displayWidth(it) }
        val lines = profile.layers.map { layer ->
            val triggerName = layer.triggerButton?.let { padToDisplayWidth(buttonDisplayName(it), maxDisplayWidth) }
            if (triggerName != null) {
                "  按住 $triggerName → 激活 ${layer.name}"
            } else {
                "  ${layer.name}: 未设置触发键（可在操作层设置中配置）"
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * 计算字符串显示宽度（中文字符/全角字符算 2，西文字符/半角算 1）
     *
     * 用于按显示宽度对齐文本，避免中英文混合时不对齐。
     * 宽度规则与默认字体渲染一致：汉字/全角（含全角空格 U+3000）占 1em=2 单位，
     * 西文字母数字/半角空格/方向箭头（↑↓←→ 在默认字体为半角）占 0.5em=1 单位。
     */
    private fun displayWidth(s: String): Int = s.sumOf { c ->
        val code = c.code
        if (code in 0x2E80..0x9FFF || code in 0xFF00..0xFFEF) 2 else 1
    }

    /**
     * 按显示宽度精确填充字符串到目标宽度
     *
     * 用**全角空格（U+3000，1em=2 单位）**补偶数余量、**半角空格（0.5em=1 单位）**补奇数余量，
     * 两者相加精确等于 [targetWidth] - 当前宽度，确保 → 箭头列严格对齐。
     */
    private fun padToDisplayWidth(s: String, targetWidth: Int): String {
        val pad = targetWidth - displayWidth(s)
        if (pad <= 0) return s
        val fullWidthSpaces = pad / 2
        val halfWidthSpaces = pad % 2
        return s + "\u3000".repeat(fullWidthSpaces) + " ".repeat(halfWidthSpaces)
    }

    /**
     * 将 ControllerButton 转换为可读名称
     *
     * 与 LayerEditActivity.buttonDisplayName 保持一致。
     */
    private fun buttonDisplayName(button: ControllerButton): String = when (button) {
        ControllerButton.A -> "A"
        ControllerButton.B -> "B"
        ControllerButton.X -> "X"
        ControllerButton.Y -> "Y"
        ControllerButton.LEFT_SHOULDER -> "LB"
        ControllerButton.RIGHT_SHOULDER -> "RB"
        ControllerButton.LEFT_TRIGGER_CLICK -> "L2"
        ControllerButton.RIGHT_TRIGGER_CLICK -> "R2"
        ControllerButton.LEFT_STICK_CLICK -> "L3"
        ControllerButton.RIGHT_STICK_CLICK -> "R3"
        ControllerButton.MENU -> "Menu"
        ControllerButton.OPTIONS -> "Options"
        ControllerButton.GUIDE -> "Guide"
        ControllerButton.DPAD_UP -> "D-Pad ↑"
        ControllerButton.DPAD_DOWN -> "D-Pad ↓"
        ControllerButton.DPAD_LEFT -> "D-Pad ←"
        ControllerButton.DPAD_RIGHT -> "D-Pad →"
        ControllerButton.TOUCHPAD_CLICK -> "Touchpad"
    }

    // ====================================================================
    // Windows 客户端文件导出
    // ====================================================================
    // 将打包在 APK assets 中的 inputbridge_client.exe 和 control.bat
    // 释放到 Download/AControler 目录，方便用户通过文件管理器或 ADB 取出，
    // 复制到 Winlator 的 C 盘使用。
    //
    // 实现策略:
    // - Android 10+ (API 29+): 使用 MediaStore.Downloads API + RELATIVE_PATH 子目录
    // - Android 9 及以下 (API < 29): 直接写入 Environment.DIRECTORY_DOWNLOADS/AControler
    // ====================================================================

    /** 导出目标子目录名 */
    private val exportDirName = "AControler"

    /** 需要导出的文件列表 (assetName → displayName) */
    private val exportFiles = listOf(
        "inputbridge_client.exe" to "inputbridge_client.exe",
        "control.bat" to "control.bat"
    )

    /**
     * 导出 Windows 客户端文件到 Download/AControler 目录
     *
     * 从 APK 的 assets 中读取 exe 和 control.bat，写入到公共 Download/AControler 目录。
     */
    private fun exportFilesToDownload() {
        val results = mutableListOf<String>()

        for ((assetName, displayName) in exportFiles) {
            // 从 assets 读取文件字节
            val bytes = try {
                assets.open(assetName).use { it.readBytes() }
            } catch (e: Exception) {
                toastLog("读取 $assetName 失败: ${e.message}", long = true)
                return
            }

            // 根据 Android 版本选择写入方式
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(bytes, displayName)
            } else {
                exportViaLegacyFile(bytes, displayName)
            }

            results.add("$displayName: ${if (success) "OK" else "FAIL"} (${bytes.size} bytes)")
        }

        // 汇总提示
        val allSuccess = results.all { it.contains("OK") }
        val msg = if (allSuccess) {
            "已导出 ${exportFiles.size} 个文件到 Download/$exportDirName 目录:\n" +
            results.joinToString("\n") { "  $it" } + "\n" +
            "请将文件复制到 Winlator 的 C 盘后运行 control.bat start"
        } else {
            "导出部分失败:\n" + results.joinToString("\n") { "  $it" }
        }
        toastLog(msg, long = true)
    }

    /**
     * 通过 MediaStore.Downloads 写入文件到子目录 (Android 10+)
     *
     * 使用 MediaStore API 写入公共 Download/AControler 目录，无需申请存储权限。
     *
     * @param bytes 文件字节数组
     * @param displayName 显示文件名
     * @return true=写入成功
     */
    private fun exportViaMediaStore(bytes: ByteArray, displayName: String): Boolean {
        return try {
            val resolver = contentResolver
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                // 指定写入 Downloads/AControler 子目录
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$exportDirName")
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: run {
                Log.e(TAG, "MediaStore insert failed for $displayName")
                return false
            }
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: run {
                Log.e(TAG, "openOutputStream failed for $displayName")
                return false
            }
            Log.i(TAG, "Exported $displayName to Download/$exportDirName (${bytes.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export $displayName failed", e)
            false
        }
    }

    /**
     * 通过直接文件写入到子目录 (Android 9 及以下)
     *
     * 直接写入 Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/AControler，
     * 需要 WRITE_EXTERNAL_STORAGE 运行时权限。
     *
     * @param bytes 文件字节数组
     * @param displayName 显示文件名
     * @return true=写入成功
     */
    private fun exportViaLegacyFile(bytes: ByteArray, displayName: String): Boolean {
        // 检查写入权限
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_STORAGE)
            return false
        }
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val targetDir = File(downloadDir, exportDirName)
            if (!targetDir.exists()) targetDir.mkdirs()
            val targetFile = File(targetDir, displayName)
            FileOutputStream(targetFile).use { output ->
                output.write(bytes)
                output.flush()
            }
            Log.i(TAG, "Exported $displayName to ${targetFile.absolutePath} (${bytes.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export $displayName failed", e)
            false
        }
    }

    companion object {
        /** 请求 WRITE_EXTERNAL_STORAGE 权限的请求码 (仅 Android 9 及以下使用) */
        private const val REQUEST_WRITE_STORAGE = 1001
        private const val TAG = "SteamLikeUI"
        /** 主界面滚动位置持久化 */
        private const val SCROLL_PREFS = "main_scroll"
        private const val KEY_SCROLL_Y = "scroll_y"
        /** Debug用: 启动 MainActivity 时传入此 extra=true 会自动启动服务并跳转到测试页面 */
        const val EXTRA_AUTO_OPEN_TEST = "auto_open_test"
    }

    // ====================================================================
    // 客户端连接状态广播接收器
    // ====================================================================
    // 接收来自 ControllerOverlayService 的连接状态广播，
    // 在 UI 上实时显示 Windows 客户端的连接/断开状态。
    // ====================================================================

    /**
     * 客户端连接状态广播接收器
     *
     * 接收 [ControllerOverlayService.ACTION_CLIENT_STATUS] 广播，
     * 更新 connectionStatusText 显示连接状态。
     */
    private val clientStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ControllerOverlayService.ACTION_CLIENT_STATUS -> {
                    val statusText = intent.getStringExtra(ControllerOverlayService.EXTRA_STATUS_TEXT)
                        ?: "unknown"
                    val connected = intent.getBooleanExtra(ControllerOverlayService.EXTRA_CONNECTED, false)
                    val displayText = if (connected) {
                        "Client: connected\n$statusText"
                    } else {
                        "Client: disconnected\n$statusText"
                    }
                    connectionStatusText.text = displayText
                    connectionStatusText.setTextColor(
                        if (connected) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt()
                    )
                    Log.i(TAG, "Connection status: connected=$connected, msg=$statusText")
                }
                ControllerOverlayService.ACTION_CAPTURE_STATUS -> {
                    // 悬浮窗暂停/恢复 → 同步 app 内捕获开关
                    val capturing = intent.getBooleanExtra(
                        ControllerOverlayService.EXTRA_CAPTURING, true
                    )
                    if (::captureSwitch.isInitialized) {
                        suppressCaptureListener = true
                        captureSwitch.isChecked = capturing
                        suppressCaptureListener = false
                    }
                    if (::captureStatusText.isInitialized) {
                        captureStatusText.text = if (capturing) {
                            "捕获状态: ✅ 运行中"
                        } else {
                            "捕获状态: ⏸ 已暂停（点悬浮窗'恢复捕获'或此开关恢复）"
                        }
                    }
                    Log.i(TAG, "Capture status: capturing=$capturing")
                }
            }
        }
    }

    /** 日志辅助方法，所有 UI 操作日志统一输出到 Logcat (tag: SteamLikeUI) */
    private fun logD(msg: String) = Log.d(TAG, msg)

    /** 日志辅助方法，Toast 同时输出到 Logcat */
    private fun toastLog(msg: String, long: Boolean = false) {
        Log.i(TAG, "Toast: $msg")
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // 注册客户端连接状态广播接收器
        // Android 14+ (API 34+) 要求指定 RECEIVER_EXPORTED 或 RECEIVER_NOT_EXPORTED
        // 此广播仅用于应用内部通信，使用 NOT_EXPORTED
        val filter = IntentFilter(ControllerOverlayService.ACTION_CLIENT_STATUS)
        filter.addAction(ControllerOverlayService.ACTION_CAPTURE_STATUS)
        ContextCompat.registerReceiver(
            this, clientStatusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        logD("onResume: registered client status receiver")
        syncCaptureSwitchFromPrefs()
        updateUI()
        updateConfigStatus()
        updateUsageStatsStatus()
        updateUsageText()

        // Debug: 自动跳转测试页面
        // 通过 `adb shell am start -n com.steamlike.controller/.MainActivity --ez auto_open_test true` 触发
        if (intent?.getBooleanExtra(EXTRA_AUTO_OPEN_TEST, false) == true) {
            // 清除 extra 避免重复跳转
            intent.removeExtra(EXTRA_AUTO_OPEN_TEST)
            ensureServiceRunning()
            if (LayerEditActivity.steamInputRef != null) {
                startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java))
            } else {
                // 等待服务初始化，最多 5 秒
                var waited = 0
                val tick = 100
                val maxWait = 5000
                configStatusText.postDelayed(object : Runnable {
                    override fun run() {
                        waited += tick
                        if (LayerEditActivity.steamInputRef != null) {
                            startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java))
                        } else if (waited < maxWait) {
                            configStatusText.postDelayed(this, tick.toLong())
                        } else {
                            toastLog("服务初始化超时，请检查", long = true)
                        }
                    }
                }, tick.toLong())
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 注销广播接收器，避免内存泄漏
        unregisterReceiver(clientStatusReceiver)
        // 保存滚动位置（下次进入/进程重建后恢复）
        if (::mainScroll.isInitialized) {
            getSharedPreferences(SCROLL_PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_SCROLL_Y, mainScroll.scrollY).apply()
        }
        logD("onPause: unregistered client status receiver")
    }

    private fun updateUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val canStart = hasOverlay

        val status = buildString {
            append("悬浮窗权限: ${if (hasOverlay) "✅ 已授予" else "❌ 未授予"}\n")
            append("就绪状态: ${if (canStart) "✅ 可以启动" else "⚠️ 请先完成上述步骤"}")
        }
        statusText.text = status

        overlayButton.isEnabled = !hasOverlay
        startButton.isEnabled = canStart

        overlayButton.text = if (hasOverlay) "悬浮窗已就绪 ✅" else "授予悬浮窗权限"
    }
}
