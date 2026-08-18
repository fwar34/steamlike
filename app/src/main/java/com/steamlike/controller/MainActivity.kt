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
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.steamlike.controller.config.ConfigManager
import com.steamlike.controller.config.ControllerConfig
import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerProfile
import com.steamlike.controller.core.GlobalSettings
import com.steamlike.controller.service.ControllerOverlayService
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
    /** 抑制捕获开关监听标志（程序化同步状态时避免循环触发） */
    private var suppressCaptureListener = false
    /** TCP监听地址输入框 */
    private lateinit var hostEditText: EditText
    /** TCP监听端口输入框 */
    private lateinit var portEditText: EditText

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

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        // 标题
        container.addView(TextView(this).apply {
            text = "SteamLike 手柄控制器\nWoW乌龟服 1.18.1"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        })

        // 状态
        statusText = TextView(this).apply {
            textSize = 13f
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, 24)
        }
        container.addView(statusText)

        // 悬浮窗权限按钮
        overlayButton = Button(this).apply {
            text = "授予悬浮窗权限"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }
        container.addView(overlayButton)

        // ===== 智能暂停（Smart Pause）=====
        // 可焦点悬浮窗在 Android 13+ 会吃掉系统右滑返回手势。
        // 智能暂停: 检测前台应用，仅当"捕获白名单"内的应用(如 Winlator)在前台时
        // 保持焦点窗口捕获手柄，其他应用自动移除焦点窗口 → 右滑返回恢复正常。
        // 需要"使用情况访问"权限（设置 → 安全 → 使用情况访问）。
        container.addView(TextView(this).apply {
            text = "\n智能暂停（修复右滑返回失效）"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })

        container.addView(TextView(this).apply {
            text = ("焦点窗口会拦截 Android 13+ 的右滑返回手势。\n"
                + "开启后自动检测前台应用：Winlator 在前台时保持手柄捕获，\n"
                + "切到其他应用自动暂停捕获，右滑返回恢复正常。\n"
                + "需要授权\"使用情况访问\"（如未授权则退化为手动暂停按钮）。")
            textSize = 11f
            setLineSpacing(0f, 1.3f)
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 0, 0, 12)
        })

        // 智能暂停开关
        smartPauseSwitch = Switch(this).apply {
            text = "启用智能暂停"
            isChecked = getSharedPreferences("smart_pause", MODE_PRIVATE)
                .getBoolean("enabled", true)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                getSharedPreferences("smart_pause", MODE_PRIVATE).edit()
                    .putBoolean("enabled", checked).apply()
                updateUsageStatsStatus()
                toastLog(if (checked) "智能暂停已开启（切出游戏自动暂停捕获）" else "智能暂停已关闭（手动模式）")
            }
        }
        container.addView(smartPauseSwitch)

        // 捕获开关（与悬浮窗暂停/恢复按钮双向同步）
        captureSwitch = Switch(this).apply {
            text = "手柄捕获"
            isChecked = getSharedPreferences("steamlike", MODE_PRIVATE)
                .getBoolean("capture_enabled", true)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (suppressCaptureListener) return@setOnCheckedChangeListener
                setCaptureSwitch(checked)
            }
        }
        container.addView(captureSwitch)

        // 捕获状态显示（实时接收服务广播同步）
        captureStatusText = TextView(this).apply {
            textSize = 11f
            setPadding(0, 4, 0, 12)
            setTextColor(0xFFAAAAAA.toInt())
        }
        container.addView(captureStatusText)

        // 白名单输入框
        container.addView(TextView(this).apply {
            text = "捕获白名单（前台在此列表内时保持手柄捕获，逗号分隔包名）"
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 8, 0, 4)
        })
        whitelistEditText = EditText(this).apply {
            setText(getSharedPreferences("smart_pause", MODE_PRIVATE)
                .getString("whitelist", ControllerOverlayService.DEFAULT_WHITELIST.joinToString(",")))
            hint = "如: com.winlator"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        container.addView(whitelistEditText)

        // 使用情况访问授权入口
        usageStatsButton = Button(this).apply {
            text = "授权使用情况访问"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        container.addView(usageStatsButton)

        usageStatsStatusText = TextView(this).apply {
            textSize = 11f
            setPadding(0, 4, 0, 0)
            setTextColor(0xFFAAAAAA.toInt())
        }
        container.addView(usageStatsStatusText)

        // ===== TCP服务器配置 =====
        container.addView(TextView(this).apply {
            text = "\nTCP服务器配置"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })

        // 地址和端口输入框（水平布局）
        val hostPortLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        hostEditText = EditText(this).apply {
            hint = "监听地址 (如 0.0.0.0)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isFocusable = true
            isFocusableInTouchMode = true
            setText(getSharedPreferences("server_config", MODE_PRIVATE).getString("host", "0.0.0.0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        portEditText = EditText(this).apply {
            hint = "端口"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isFocusable = true
            isFocusableInTouchMode = true
            setText(getSharedPreferences("server_config", MODE_PRIVATE).getString("port", "27015"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        hostPortLayout.addView(hostEditText)
        hostPortLayout.addView(portEditText)
        container.addView(hostPortLayout)

        // 启动服务按钮
        startButton = Button(this).apply {
            text = "启动手柄映射"
            setOnClickListener {
                val host = hostEditText.text.toString().trim()
                val portStr = portEditText.text.toString().trim()
                val port = portStr.toIntOrNull() ?: 27015
                // 保存到SharedPreferences
                getSharedPreferences("server_config", MODE_PRIVATE).edit()
                    .putString("host", host)
                    .putString("port", portStr)
                    .apply()
                saveSmartPausePrefs()
                val intent = Intent(this@MainActivity, ControllerOverlayService::class.java).apply {
                    putExtra(ControllerOverlayService.EXTRA_HOST, host)
                    putExtra(ControllerOverlayService.EXTRA_PORT, port)
                    putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, smartPauseSwitch.isChecked)
                    putExtra(ControllerOverlayService.EXTRA_WHITELIST, whitelistEditText.text.toString().trim())
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
                statusText.append("\n\n✅ 服务已启动！监听 ${host.ifBlank { "0.0.0.0" }}:$port")
                connectionStatusText.text = "Client: waiting for connection..."
                logD("Start button clicked, host=$host port=$port")
            }
        }
        container.addView(startButton)

        // 停止按钮
        container.addView(Button(this).apply {
            text = "停止服务"
            setOnClickListener {
                val intent = Intent(this@MainActivity, ControllerOverlayService::class.java)
                intent.action = ControllerOverlayService.ACTION_STOP
                startService(intent)
                stopService(Intent(this@MainActivity, ControllerOverlayService::class.java))
                connectionStatusText.text = "Client: service stopped"
                updateUI()
            }
        })

        // ===== 客户端连接状态 =====
        container.addView(TextView(this).apply {
            text = "\nConnection Status"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })

        connectionStatusText = TextView(this).apply {
            text = "Client: not started"
            textSize = 13f
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, 12)
            setTextColor(0xFFAAAAAA.toInt())
        }
        container.addView(connectionStatusText)

        // ===== 配置管理 =====
        container.addView(TextView(this).apply {
            text = "\n配置管理"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })

        // 配置状态
        configStatusText = TextView(this).apply {
            textSize = 12f
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, 12)
        }
        container.addView(configStatusText)

        // 导出配置按钮
        container.addView(Button(this).apply {
            text = "导出配置"
            setOnClickListener {
                // 先确保服务已启动
                ensureServiceRunning()
                // 启动 SAF 创建文档
                createDocumentLauncher.launch("steamlike_config.json")
            }
        })

        // 导入配置按钮
        container.addView(Button(this).apply {
            text = "导入配置"
            setOnClickListener {
                // 先确保服务已启动
                ensureServiceRunning()
                // 启动 SAF 打开文档
                openDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
        })

        // 重置为默认按钮
        container.addView(Button(this).apply {
            text = "重置为默认配置"
            setOnClickListener {
                ensureServiceRunning()
                val intent = Intent(this@MainActivity, ControllerOverlayService::class.java)
                intent.action = ControllerOverlayService.ACTION_RESET_CONFIG
                ContextCompat.startForegroundService(this@MainActivity, intent)
                toastLog("正在重置...")
                // 延迟刷新UI
                configStatusText.postDelayed({ updateConfigStatus() }, 1000)
            }
        })

        // 操作层设置按钮
        container.addView(Button(this).apply {
            text = "操作层设置"
            setOnClickListener {
                // 必须先启动服务（LayerEditActivity.steamInputRef 在服务启动后才非空）
                ensureServiceRunning()
                if (LayerEditActivity.steamInputRef == null) {
                    // 服务正在异步初始化，提示并轮询等待最多3秒
                    toastLog("服务正在初始化，请稍候...")
                    var waited = 0
                    val tick = 100
                    val maxWait = 3000
                    configStatusText.postDelayed(object : Runnable {
                        override fun run() {
                            waited += tick
                            if (LayerEditActivity.steamInputRef != null) {
                                startActivity(Intent(this@MainActivity, LayerEditActivity::class.java))
                            } else if (waited < maxWait) {
                                configStatusText.postDelayed(this, tick.toLong())
                            } else {
                                toastLog("服务初始化超时，请重试", long = true)
                            }
                        }
                    }, tick.toLong())
                    return@setOnClickListener
                }
                val intent = Intent(this@MainActivity, LayerEditActivity::class.java)
                startActivity(intent)
            }
        })

        // ===== 右摇杆优化设置 =====
        container.addView(TextView(this).apply {
            text = "\n右摇杆优化设置"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })

        // 总体说明
        container.addView(TextView(this).apply {
            text = ("右摇杆控制鼠标视角时，可通过以下参数调节手感。\n"
                + "• 觉得滑动过快 → 降低「灵敏度」或提高「加速曲线指数」\n"
                + "• 觉得不够流畅/抖动 → 提高「平滑系数」(0.5~0.8 推荐)\n"
                + "• 摇杆居中时仍在漂移 → 提高「死区」(0.1~0.25)\n"
                + "• 轻推难以精确瞄准 → 降低「加速曲线指数」靠近 1.0")
            textSize = 11f
            setLineSpacing(0f, 1.3f)
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 0, 0, 16)
        })

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
        container.addView(deadzoneEdit.first)

        // 视角灵敏度
        val sensitivityEdit = makeSettingsEdit(
            title = "右摇杆视角灵敏度 (Look Sensitivity)",
            hint = "0.1 ~ 5.0，默认 0.5",
            desc = ("右摇杆控制视角/鼠标移动的整体速度倍率。\n"
                + "数值越大移动越快。觉得滑动过快请降低此值(如 0.3)；过慢可提高(如 0.8)。"),
            value = currentSettings.lookSensitivity
        )
        container.addView(sensitivityEdit.first)

        // 平滑系数
        val smoothingEdit = makeSettingsEdit(
            title = "视角平滑系数 (Look Smoothing)",
            hint = "0.0 ~ 0.95，默认 0.5",
            desc = ("指数移动平均(EMA)滤波系数，降低摇杆抖动让移动更顺滑。\n"
                + "0=关闭平滑(最跟手但有抖动)，越大越顺滑但延迟增加。\n"
                + "推荐: 0.3~0.7。觉卡顿降一点，觉延迟降一点。"),
            value = currentSettings.lookSmoothing
        )
        container.addView(smoothingEdit.first)

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
        container.addView(accelerationEdit.first)

        // 光标速度倍率
        val cursorSpeedEdit = makeSettingsEdit(
            title = "光标移动速度倍率 (Cursor Speed)",
            hint = "默认 1.0",
            desc = ("左摇杆/其他鼠标移动场景的速度倍率(右摇杆视角由灵敏度控制)。\n"
                + ">1.0 更快，<1.0 更慢。一般保持 1.0 即可。"),
            value = currentSettings.cursorSpeed
        )
        container.addView(cursorSpeedEdit.first)

        // 保存按钮
        container.addView(Button(this).apply {
            text = "保存右摇杆设置"
            setOnClickListener {
                val dz = deadzoneEdit.second.text.toString().trim().toFloatOrNull()
                val sens = sensitivityEdit.second.text.toString().trim().toFloatOrNull()
                val sm = smoothingEdit.second.text.toString().trim().toFloatOrNull()
                val ac = accelerationEdit.second.text.toString().trim().toFloatOrNull()
                val cs = cursorSpeedEdit.second.text.toString().trim().toFloatOrNull()
                if (dz == null || sens == null || sm == null || ac == null || cs == null) {
                    toastLog("请填写全部 5 个数值", long = true)
                    return@setOnClickListener
                }
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    toastLog("请先授予悬浮窗权限并启动服务", long = true)
                    return@setOnClickListener
                }
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
        })

        // ===== Windows 客户端导出 =====
        container.addView(TextView(this).apply {
            text = "\nWindows 客户端"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })

        // 导出 Windows 客户端文件到 Download/AControler 目录按钮
        container.addView(Button(this).apply {
            text = "导出 Windows 客户端到 Download/AControler"
            setOnClickListener {
                exportFilesToDownload()
            }
        })

        // ===== 调试: 手柄按键测试 =====
        container.addView(TextView(this).apply {
            text = "\n调试"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })

        container.addView(Button(this).apply {
            text = "测试手柄按键（模拟器调试用）"
            setOnClickListener {
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
                    return@setOnClickListener
                }
                startActivity(Intent(this@MainActivity, GamepadTestActivity::class.java))
            }
        })

        // 使用说明（动态根据当前 profile 生成操作层切换说明）
        usageTextView = TextView(this).apply {
            textSize = 11f
            setLineSpacing(0f, 1.3f)
            setPadding(0, 32, 0, 0)
            setTextColor(0xFFAAAAAA.toInt())
        }
        container.addView(usageTextView)

        scroll.addView(container)
        setContentView(scroll)
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
        // 读取用户配置的host和port
        saveSmartPausePrefs()
        val prefs = getSharedPreferences("server_config", MODE_PRIVATE)
        val host = prefs.getString("host", "0.0.0.0") ?: "0.0.0.0"
        val port = prefs.getString("port", "27015")?.toIntOrNull() ?: 27015
        val intent = Intent(this, ControllerOverlayService::class.java).apply {
            putExtra(ControllerOverlayService.EXTRA_HOST, host)
            putExtra(ControllerOverlayService.EXTRA_PORT, port)
            putExtra(ControllerOverlayService.EXTRA_SMART_PAUSE, smartPauseSwitch.isChecked)
            putExtra(ControllerOverlayService.EXTRA_WHITELIST, whitelistEditText.text.toString().trim())
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
     * 保存智能暂停配置到 SharedPreferences
     */
    private fun saveSmartPausePrefs() {
        getSharedPreferences("smart_pause", MODE_PRIVATE).edit()
            .putBoolean("enabled", smartPauseSwitch.isChecked)
            .putString("whitelist", whitelistEditText.text.toString().trim())
            .apply()
    }

    /**
     * 捕获开关变化 → 持久化 + 通知服务（悬浮窗同步）
     *
     * @param enabled true=恢复捕获, false=暂停捕获
     */
    private fun setCaptureSwitch(enabled: Boolean) {
        getSharedPreferences("steamlike", MODE_PRIVATE).edit()
            .putBoolean("capture_enabled", enabled).apply()
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
     * 从 SharedPreferences 同步捕获开关状态（onResume 时调用）
     */
    private fun syncCaptureSwitchFromPrefs() {
        if (!::captureSwitch.isInitialized) return
        val enabled = getSharedPreferences("steamlike", MODE_PRIVATE)
            .getBoolean("capture_enabled", true)
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
     * 操作层切换说明根据当前 profile 的 triggerButton 动态生成，
     * 不再硬编码 LB+D-Pad 等组合。公共层和操作层的内部按键映射
     * 不在使用说明中列出，用户可通过"操作层设置"按钮查看具体映射。
     */
    private fun updateUsageText() {
        // 动态生成操作层切换说明
        val layerSwitchLines = buildLayerSwitchLines()

        usageTextView.text = """
            使用说明:

            第一步: Android端准备
            1. 授予悬浮窗权限
            2. 点击"启动手柄映射"
            3. 切换到Winlator运行游戏

            第二步: Windows端准备
            1. 点击"导出 Windows 客户端到 Download/AControler"按钮
               (exe 和 control.bat 已内置在 APK 中)
            2. 从 Download/AControler 目录取出文件:
               - inputbridge_client.exe
               - control.bat
            3. 将这两个文件复制到Winlator的C盘
            4. 在Winlator中运行: control.bat start
               (或直接运行: inputbridge_client.exe)
            5. 保持窗口打开, 切到WoW游戏

            架构: Android(焦点窗口捕获手柄 + TCP服务器:27015)
                  ←→ Windows(SendInput注入)

            操作层切换（按住触发键激活，松开回公共层）:
            $layerSwitchLines

            配置管理:
              操作层设置 → 打开设置界面，可视化编辑每个操作层的按键映射
              导出配置 → 将当前按键映射保存为 JSON 文件
              导入配置 → 从 JSON 文件加载按键映射
              重置配置 → 恢复默认 WoW 预设
              配置文件格式见 config/ControllerConfig.kt

            操作层设置界面:
              - 顶部下拉框切换操作层（公共层 + Layer1-Layer10）
              - 点击按键映射列表项编辑单个按键
                可选: 键盘按键/鼠标点击/切换操作层
                每个映射可添加最多3个子命令形成组合键（如 Alt+3）
              - "名称"按钮修改操作层名称
              - "触发"按钮设置该层触发按键（公共层禁用）
              - 按住触发按键激活层，松开回到公共层
              - 进入设置界面时悬浮窗自动隐藏（避免遮挡手势）
                退出后自动恢复

            悬浮窗操作:
              - 默认收起显示当前激活层名（无激活层时显示"公共层"）
              - 操作层切换时收起悬浮窗文本同步刷新
              - 可拖动到任意位置，点击展开面板
              - 点击"收起"回到收起状态
              - 层按钮按住激活对应层，松开回公共层
              - 点击"清除层"清除所有激活层
              - 点击"关闭"停止服务
        """.trimIndent()
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
            setPadding(0, 12, 0, 12)
        }
        layout.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 4)
        })
        layout.addView(TextView(this).apply {
            text = desc
            textSize = 10f
            setLineSpacing(0f, 1.3f)
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 4)
        })
        val edit = EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            isFocusable = true
            isFocusableInTouchMode = true
            setText(value.toString())
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
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
     * 计算字符串显示宽度（中文字符算 2，西文字符算 1）
     *
     * 用于按显示宽度对齐文本，避免等宽字体下中英文混合时不对齐。
     */
    private fun displayWidth(s: String): Int = s.sumOf { c ->
        // CJK 统一汉字 + CJK 符号 + 全角字符算 2，否则算 1
        if (c.code in 0x2E80..0x9FFF || c.code in 0xFF00..0xFFEF) 2 else 1
    }

    /**
     * 按显示宽度填充字符串到目标宽度
     *
     * 在字符串末尾补充空格，使其显示宽度等于 [targetWidth]。
     * 用于让后续的 → 箭头对齐。
     */
    private fun padToDisplayWidth(s: String, targetWidth: Int): String {
        val pad = targetWidth - displayWidth(s)
        return if (pad > 0) s + " ".repeat(pad) else s
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
