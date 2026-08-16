package com.steamlike.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.steamlike.controller.config.ConfigManager
import com.steamlike.controller.service.ControllerOverlayService
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var overlayButton: Button
    private lateinit var configStatusText: TextView
    private lateinit var connectionStatusText: TextView

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

        // 启动服务按钮
        startButton = Button(this).apply {
            text = "启动手柄映射"
            setOnClickListener {
                val intent = Intent(this@MainActivity, ControllerOverlayService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, intent)
                // 提示切换到Winlator
                statusText.append("\n\n✅ 服务已启动！请切换到Winlator")
                connectionStatusText.text = "Client: waiting for connection..."
                logD("Start button clicked, service starting")
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

        // 使用说明
        container.addView(TextView(this).apply {
            text = """
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

                公共层（默认按键映射）:
                  A=跳跃  B=互动  X=攻击  Y=背包
                  LB=选怪  RB=面向  L3=自动跑  R3=回复
                  LT=Shift  RT=鼠标左键
                  D-Pad=快捷栏1-4
                  左摇杆=移动  右摇杆=视角
                  MENU=Esc  OPTIONS=回车  GUIDE=地图

                组合键（参考Steam子指令）:
                  A+RB=选怪   B+RB=面向目标
                  X+RB=回复   Y+RB=地图
                  D-Pad+L3=快捷栏5-8
                  D-Pad+R3=快捷栏9/0/-/=
                  (D-Pad单独=快捷栏1-4, 共12栏全覆盖)

                10个操作层（可叠加, 继承公共层）:
                  战斗 - A/B/X/Y→技能5-8, D-Pad→9/0/-/=
                  骑乘 - 移动摇杆更灵敏
                  瞄准 - 右摇杆更精准、移动更慢
                  拾取 - A→右键拾取, X→左键全拿
                  潜行 - A→潜行, 移动更小心
                  钓鱼 - A→钓鱼
                  对战 - A/B/X/Y→5-8, 摇杆更快
                  团本 - D-Pad→团队标记
                  旅行 - A→自动跑, B→坐骑
                  自定义 - 空层(可运行时配置)

                快捷键:
                  LB + D-Pad 上/下/左/右 → 切换 战斗/骑乘/瞄准/拾取
                  LB + A/B/X/Y           → 切换 潜行/钓鱼/对战/团本
                  LB + L3/R3             → 切换 旅行/自定义
                  LB + HOME              → 清除所有层

                配置管理:
                  导出配置 → 将当前按键映射保存为 JSON 文件
                  导入配置 → 从 JSON 文件加载按键映射
                  重置配置 → 恢复默认 WoW 预设
                  配置文件格式见 config/ControllerConfig.kt

                悬浮窗可拖动到任意位置
            """.trimIndent()
            textSize = 11f
            setLineSpacing(0f, 1.3f)
            setPadding(0, 32, 0, 0)
            setTextColor(0xFFAAAAAA.toInt())
        })

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
        val intent = Intent(this, ControllerOverlayService::class.java)
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
        val cm = ConfigManager(this)
        val hasConfig = cm.hasConfigFile()
        val configFile = ConfigManager.getConfigFile(this)
        configStatusText.text = if (hasConfig) {
            "配置文件: 已加载\n路径: ${configFile.name}\n大小: ${configFile.length()} 字节"
        } else {
            "配置文件: 未加载（使用默认 WoW 预设）"
        }
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
            if (intent?.action == ControllerOverlayService.ACTION_CLIENT_STATUS) {
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
        ContextCompat.registerReceiver(
            this, clientStatusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        logD("onResume: registered client status receiver")
        updateUI()
        updateConfigStatus()
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
