package com.steamlike.controller

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.steamlike.controller.config.AppConfigStore
import com.steamlike.controller.config.ConfigManager
import com.steamlike.controller.config.ControllerConfig
import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.service.ControllerOverlayService
import com.steamlike.controller.core.ControllerProfile
import com.steamlike.controller.core.KeyMapping
import com.steamlike.controller.core.MappedAction
import com.steamlike.controller.core.MouseButton
import com.steamlike.controller.core.OperationLayer
import com.steamlike.controller.core.SteamInput
import java.io.File

/**
 * 操作层设置 Activity
 *
 * 用于编辑手柄控制器的操作层和按键映射配置。
 *
 * ## 功能
 * 1. 选择操作层（Common + Layer1-Layer10），通过顶部下拉框切换
 * 2. 显示当前操作层所有手柄按键的映射情况（列表形式）
 * 3. 点击某个按键进入编辑对话框，可设置映射功能:
 *    - 键盘按键（字母A-Z、数字0-9、功能键F1-F12、修饰键、符号键等）
 *    - 鼠标点击（左键/中键/右键）
 *    - 切换操作层（选择目标层）
 * 4. 每个映射可添加最多3个子命令（子命令也是键盘按键，用于组合键）
 * 5. 可设置操作层名称和触发按键
 *
 * ## 数据流
 * ```
 * ControllerOverlayService 创建 SteamInput 实例
 *      ↓ 设置 LayerEditActivity.steamInputRef
 * LayerEditActivity 通过 steamInputRef?.profile 获取配置
 *      ↓ 用户编辑映射
 * 修改 OperationLayer.buttonMappings（MutableMap，可直接修改）
 *      ↓ 保存
 * steamInputRef?.loadProfile(newProfile) → 更新运行时
 * ConfigManager.saveToInternal(profile) → 持久化到内部存储
 * ```
 *
 * ## Android 知识点
 * - **Spinner**: 下拉选择器，类似 HTML 的 `<select>`。通过 `ArrayAdapter` 提供选项数据。
 * - **ListView**: 列表视图，通过 `ArrayAdapter` 提供数据，`setOnItemClickListener` 处理点击。
 * - **AlertDialog**: 模态对话框，通过 `AlertDialog.Builder` 构建，支持自定义视图。
 * - **LayoutInflater**: 将 XML 布局文件实例化为 View 对象。
 * - **ArrayAdapter**: 通用适配器，将数组/列表数据绑定到 AdapterView（如 ListView/Spinner）。
 */
class LayerEditActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LayerEditActivity"

        /**
         * SteamInput 实例引用
         *
         * 由 [ControllerOverlayService] 在创建 SteamInput 后赋值，
         * LayerEditActivity 通过此引用获取和修改配置。
         *
         * ## 使用方式
         * ```kotlin
         * val profile = steamInputRef?.profile  // 获取当前配置
         * steamInputRef?.loadProfile(newProfile)  // 更新配置
         * ```
         *
         * ## 线程安全
         * 此变量为 `var`，在服务主线程写入，Activity 主线程读取。
         * 引用赋值是原子的，但建议在主线程操作。
         */
        var steamInputRef: SteamInput? = null

        /** Intent extra 键: 初始选中的操作层名称 */
        const val EXTRA_LAYER_NAME = "layer_name"

        /**
         * 键盘按键选项列表（显示名称 → Android KeyCode）
         *
         * 用于按键映射编辑对话框中的按键选择 Spinner。
         * 包含字母、数字、功能键、修饰键、符号键、导航键等常用按键。
         *
         * ## Android KeyCode
         * Android 使用 [KeyEvent.KEYCODE_*] 常量表示按键，例如:
         * - KEYCODE_A = 29, KEYCODE_Z = 54
         * - KEYCODE_0 = 7, KEYCODE_9 = 16
         * - KEYCODE_F1 = 131, KEYCODE_F12 = 142
         */
        val keyboardKeyOptions: List<Pair<String, Int>> = buildKeyboardKeyOptions()

        /**
         * 构建键盘按键选项列表
         *
         * 按类别组织: 字母 → 数字 → 功能键 → 修饰键 → 特殊键 → 方向键 → 符号键 → 锁定键 → 导航键
         */
        private fun buildKeyboardKeyOptions(): List<Pair<String, Int>> {
            val list = mutableListOf<Pair<String, Int>>()

            // ===== 字母 A-Z =====
            // KEYCODE_A 到 KEYCODE_Z 是连续的 (29~54)
            for (c in 'A'..'Z') {
                val keyCode = KeyEvent.KEYCODE_A + (c - 'A')
                list.add(c.toString() to keyCode)
            }

            // ===== 数字 0-9 =====
            // KEYCODE_0 到 KEYCODE_9 是连续的 (7~16)
            for (d in '0'..'9') {
                val keyCode = KeyEvent.KEYCODE_0 + (d - '0')
                list.add(d.toString() to keyCode)
            }

            // ===== 功能键 F1-F12 =====
            // KEYCODE_F1 到 KEYCODE_F12 是连续的 (131~142)
            for (i in 1..12) {
                val keyCode = KeyEvent.KEYCODE_F1 + (i - 1)
                list.add("F$i" to keyCode)
            }

            // ===== 修饰键 =====
            list.add("Shift" to KeyEvent.KEYCODE_SHIFT_LEFT)
            list.add("Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT)
            list.add("Alt" to KeyEvent.KEYCODE_ALT_LEFT)

            // ===== 特殊键 =====
            list.add("Space" to KeyEvent.KEYCODE_SPACE)
            list.add("Enter" to KeyEvent.KEYCODE_ENTER)
            list.add("Tab" to KeyEvent.KEYCODE_TAB)
            list.add("Esc" to KeyEvent.KEYCODE_ESCAPE)
            list.add("Backspace" to KeyEvent.KEYCODE_DEL)
            list.add("Back" to KeyEvent.KEYCODE_BACK)

            // ===== 方向键 =====
            list.add("↑" to KeyEvent.KEYCODE_DPAD_UP)
            list.add("↓" to KeyEvent.KEYCODE_DPAD_DOWN)
            list.add("←" to KeyEvent.KEYCODE_DPAD_LEFT)
            list.add("→" to KeyEvent.KEYCODE_DPAD_RIGHT)

            // ===== 符号键 =====
            list.add("-" to KeyEvent.KEYCODE_MINUS)
            list.add("=" to KeyEvent.KEYCODE_EQUALS)
            list.add("[" to KeyEvent.KEYCODE_LEFT_BRACKET)
            list.add("]" to KeyEvent.KEYCODE_RIGHT_BRACKET)
            list.add(";" to KeyEvent.KEYCODE_SEMICOLON)
            list.add("'" to KeyEvent.KEYCODE_APOSTROPHE)
            list.add("\\" to KeyEvent.KEYCODE_BACKSLASH)
            list.add("," to KeyEvent.KEYCODE_COMMA)
            list.add("." to KeyEvent.KEYCODE_PERIOD)
            list.add("/" to KeyEvent.KEYCODE_SLASH)
            list.add("`" to KeyEvent.KEYCODE_GRAVE)

            // ===== 锁定键 =====
            list.add("CapsLock" to KeyEvent.KEYCODE_CAPS_LOCK)
            list.add("NumLock" to KeyEvent.KEYCODE_NUM_LOCK)
            list.add("ScrollLock" to KeyEvent.KEYCODE_SCROLL_LOCK)

            // ===== 导航键 =====
            list.add("Insert" to KeyEvent.KEYCODE_INSERT)
            list.add("Home" to KeyEvent.KEYCODE_HOME)
            list.add("PageUp" to KeyEvent.KEYCODE_PAGE_UP)
            list.add("PageDown" to KeyEvent.KEYCODE_PAGE_DOWN)
            list.add("End" to KeyEvent.KEYCODE_MOVE_END)

            return list
        }

        /**
         * 鼠标按键选项列表（显示名称 → MouseButton 枚举）
         */
        val mouseButtonOptions: List<Pair<String, MouseButton>> = listOf(
            "鼠标左键" to MouseButton.LEFT,
            "鼠标中键" to MouseButton.MIDDLE,
            "鼠标右键" to MouseButton.RIGHT,
            "鼠标前进键" to MouseButton.FORWARD,
            "鼠标后退键" to MouseButton.BACK
        )

        /**
         * 将手柄按键枚举转换为人类可读的显示名称
         *
         * 使用 Steam/Xbox 风格命名（LB/RB/L2/R2/L3/R3 等）
         *
         * @param button 手柄按键枚举值
         * @return 可读名称（如 "A"、"LB"、"D-Pad ↑"）
         */
        fun buttonDisplayName(button: ControllerButton): String = when (button) {
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
    }

    // ====================================================================
    // UI 元素
    // ====================================================================

    /** 操作层选择下拉框 */
    private lateinit var layerSpinner: Spinner

    /** 按键映射列表 */
    private lateinit var mappingsListView: ListView

    /** 编辑层名称按钮 */
    private lateinit var editLayerNameButton: Button

    /** 编辑触发按键按钮 */
    private lateinit var editTriggerButton: Button

    /** 保存配置按钮 */
    private lateinit var saveButton: Button

    // ====================================================================
    // 状态
    // ====================================================================

    /** 当前选中的操作层 */
    private var currentLayer: OperationLayer? = null

    /**
     * 当前编辑的控制器配置（本地副本）
     *
     * 不依赖手柄映射服务运行：优先取服务运行时 [SteamInput.profile]，
     * 否则从配置文件加载，编辑后写回文件。服务运行时同步到 [SteamInput]。
     */
    private var profile: ControllerProfile = ControllerProfile.createDefault()

    /** 所有操作层名称列表（用于 Spinner 选项） */
    private var layerNames: List<String> = emptyList()

    /**
     * 抑制 Spinner 监听器标志
     *
     * 当程序化更新 Spinner 的 adapter 和 selection 时，阻止 onItemSelected 回调执行。
     * 避免在重建列表时触发不必要的 loadLayer 调用。
     */
    private var suppressLayerSpinnerListener = false

    // ====================================================================
    // 生命周期
    // ====================================================================

    /**
     * Activity 创建时调用
     *
     * 初始化 UI、加载配置、设置事件监听。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layer_edit)

        // 启用 ActionBar 返回箭头（替代边缘滑动返回手势，模拟器/手机上更可靠）
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        // 加载配置（优先服务运行时 profile，否则从配置文件加载，不依赖服务运行）
        profile = loadProfile()

        // 暂停悬浮窗：全屏焦点窗口会拦截系统手势（边缘返回滑动），
        // 进入设置界面时移除悬浮窗和焦点窗口，让出屏幕给设置界面。
        // 仅在服务运行时发送（避免未运行时被 startForegroundService 拉起）
        if (steamInputRef != null) {
            sendOverlayAction(ControllerOverlayService.ACTION_PAUSE_OVERLAY)
        }

        // 初始化 UI 元素
        layerSpinner = findViewById(R.id.spinner_layer)
        mappingsListView = findViewById(R.id.list_mappings)
        editLayerNameButton = findViewById(R.id.btn_edit_layer_name)
        editTriggerButton = findViewById(R.id.btn_edit_trigger)
        saveButton = findViewById(R.id.btn_save)

        // 设置操作层选择 Spinner
        setupLayerSpinner()

        // 设置按钮点击事件
        editLayerNameButton.setOnClickListener { showLayerNameEditDialog() }
        editTriggerButton.setOnClickListener { showTriggerButtonEditDialog() }
        saveButton.setOnClickListener { saveProfile(showToast = true) }

        // 设置按键映射列表点击事件（点击某个按键进入编辑对话框）
        mappingsListView.setOnItemClickListener { _, _, position, _ ->
            // 根据 position 获取对应的 ControllerButton 枚举值
            val button = ControllerButton.values()[position]
            showMappingEditDialog(button)
        }

        Log.i(TAG, "LayerEditActivity created, profile layers: ${profile.allLayers.size}")
    }

    /**
     * 加载控制器配置（不依赖服务运行）
     *
     * 优先级：服务运行时 [SteamInput.profile] → 内部配置文件 → 默认配置。
     */
    private fun loadProfile(): ControllerProfile {
        steamInputRef?.profile?.let { return it }
        val file = File(filesDir, ConfigManager.CONFIG_FILE_NAME)
        if (file.exists()) {
            return try {
                ControllerConfig.fromJson(file.readText())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse config, using default", e)
                ControllerProfile.createDefault()
            }
        }
        return ControllerProfile.createDefault()
    }

    /**
     * 处理 ActionBar 菜单项点击
     *
     * 处理返回箭头（home）点击，等价于按返回键。
     *
     * ## Android 知识点: ActionBar 返回箭头
     * - `setDisplayHomeAsUpEnabled(true)` 在 ActionBar 左侧显示返回箭头
     * - 点击箭头会触发 [android.R.id.home] 的 onOptionsItemSelected
     * - 必须手动调用 finish() 才能返回（不会自动返回）
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Activity 销毁时调用
     *
     * 通知 ControllerOverlayService 恢复悬浮窗和焦点窗口。
     *
     * ## 重要性
     * 必须在 onDestroy 调用 RESUME_OVERLAY，否则悬浮窗不会重建，
     * 用户将无法看到状态、激活层，也无法接收手柄事件。
     */
    override fun onDestroy() {
        super.onDestroy()
        sendOverlayAction(ControllerOverlayService.ACTION_RESUME_OVERLAY)
    }

    /**
     * 发送 overlay 控制意图到 ControllerOverlayService
     *
     * 用于通知服务暂停/恢复悬浮窗。
     *
     * @param action ControllerOverlayService.ACTION_PAUSE_OVERLAY
     *               或 ControllerOverlayService.ACTION_RESUME_OVERLAY
     */
    private fun sendOverlayAction(action: String) {
        val intent = Intent(this, ControllerOverlayService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
    }

    // ====================================================================
    // 初始化方法
    // ====================================================================

    /**
     * 设置操作层选择 Spinner
     *
     * 从 SteamInput.profile 获取所有操作层名称，填充到 Spinner。
     * 如果 Intent 携带了 EXTRA_LAYER_NAME，则初始选中该层。
     */
    private fun setupLayerSpinner() {
        val profile = this.profile

        // 获取所有层名称（Common + Layer1-Layer10）
        layerNames = profile.allLayers.map { it.name }

        // 创建适配器并设置到 Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, layerNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        layerSpinner.adapter = adapter

        // 获取 Intent 传递的初始层名称，默认选第一个
        val initialLayerName = intent.getStringExtra(EXTRA_LAYER_NAME) ?: layerNames.first()
        val initialPos = layerNames.indexOf(initialLayerName).coerceAtLeast(0)
        layerSpinner.setSelection(initialPos)

        // 设置 Spinner 选择监听器（用户切换操作层时刷新列表）
        layerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 程序化更新时跳过（避免重建列表时的重复调用）
                if (suppressLayerSpinnerListener) return
                loadLayer(layerNames[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 加载初始层
        loadLayer(layerNames[initialPos])
    }

    /**
     * 加载指定名称的操作层
     *
     * 从 SteamInput.profile 查找操作层，更新 currentLayer 并刷新 UI。
     *
     * @param name 操作层名称（如 "Common"、"Layer1"）
     */
    private fun loadLayer(name: String) {
        val profile = this.profile
        currentLayer = profile.findLayer(name)
        refreshMappingsList()
        updateLayerInfoButtons()
        Log.d(TAG, "Loaded layer: $name, mappings: ${currentLayer?.buttonMappings?.size}")
    }

    /**
     * 刷新按键映射列表
     *
     * 遍历所有 [ControllerButton] 枚举值，显示每个按键的映射情况。
     * 未设置映射的按键显示 "[未设置]"。
     */
    private fun refreshMappingsList() {
        val layer = currentLayer ?: return

        // 构建显示列表: 每个手柄按键一行
        val items = ControllerButton.values().map { button ->
            val mapping = layer.buttonMappings[button]
            val desc = mapping?.describe() ?: "[未设置]"
            "${buttonDisplayName(button)} → $desc"
        }

        // 使用 ArrayAdapter 绑定数据到 ListView
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        mappingsListView.adapter = adapter
    }

    /**
     * 更新层信息按钮的显示文字和状态
     *
     * - 编辑名称按钮: 显示当前层名称
     * - 编辑触发按键按钮: 显示当前触发按键（Common 层禁用）
     */
    private fun updateLayerInfoButtons() {
        val layer = currentLayer
        val profile = this.profile

        if (layer == null) {
            editLayerNameButton.text = "层名称"
            editTriggerButton.text = "触发按键"
            editTriggerButton.isEnabled = false
        } else {
            editLayerNameButton.text = "名称: ${layer.name}"
            editTriggerButton.text = if (layer.triggerButton != null) {
                "触发: ${buttonDisplayName(layer.triggerButton)}"
            } else {
                "触发: 无"
            }
            // 公共层（Common）始终激活，不能设置触发按键
            val isCommon = (layer === profile?.commonLayer)
            editTriggerButton.isEnabled = !isCommon
        }
    }

    // ====================================================================
    // 编辑对话框
    // ====================================================================

    /**
     * 显示按键映射编辑对话框
     *
     * 对话框内容:
     * 1. 动作类型 Spinner（键盘按键 / 鼠标点击 / 切换操作层）
     * 2. 动作值 Spinner（根据动作类型动态切换选项）
     * 3. 子命令区域（最多3个键盘按键，切换操作层时隐藏）
     *
     * 保存时构建 [KeyMapping] 并写入当前操作层的 buttonMappings。
     *
     * @param button 正在编辑的手柄按键
     */
    private fun showMappingEditDialog(button: ControllerButton) {
        val layer = currentLayer ?: return
        val existingMapping = layer.buttonMappings[button]

        // 从布局文件加载对话框视图
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_mapping_edit, null)

        // 获取对话框中的 UI 元素
        val tvButtonName = dialogView.findViewById<TextView>(R.id.tv_button_name)
        val spinnerActionType = dialogView.findViewById<Spinner>(R.id.spinner_action_type)
        val tvActionLabel = dialogView.findViewById<TextView>(R.id.tv_action_label)
        val spinnerActionValue = dialogView.findViewById<Spinner>(R.id.spinner_action_value)
        val layoutSubCommands = dialogView.findViewById<LinearLayout>(R.id.layout_sub_commands)
        val layoutSubCommandList = dialogView.findViewById<LinearLayout>(R.id.layout_sub_command_list)
        val btnAddSubCommand = dialogView.findViewById<Button>(R.id.btn_add_sub_command)

        // 显示正在编辑的按键名称
        tvButtonName.text = "编辑按键: ${buttonDisplayName(button)}"

        // ===== 设置动作类型 Spinner =====
        // 0=未设置(取消映射), 1=键盘按键, 2=鼠标点击, 3=鼠标长按, 4=切换操作层
        val actionTypes = listOf("未设置", "键盘按键", "鼠标点击", "鼠标长按", "切换操作层")
        spinnerActionType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actionTypes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // 从已有映射确定初始动作类型
        val initialActionType = when (existingMapping?.action) {
            is MappedAction.KeyboardKey -> 1
            is MappedAction.MouseClick -> 2
            is MappedAction.MouseToggle -> 3
            is MappedAction.SwitchLayer -> 4
            is MappedAction.MouseMove, is MappedAction.LookAround -> 0  // 摇杆专用，显示未设置
            null -> 0  // 未映射 → 未设置
        }

        // 先设置动作值 Spinner 的适配器（基于初始类型）
        setupActionValueSpinner(spinnerActionValue, tvActionLabel, initialActionType)

        // 设置初始动作类型选择
        spinnerActionType.setSelection(initialActionType)

        // 设置初始动作值选择（基于已有映射）
        when (val action = existingMapping?.action) {
            is MappedAction.KeyboardKey -> {
                val pos = keyboardKeyOptions.indexOfFirst { it.second == action.keyCode }
                if (pos >= 0) spinnerActionValue.setSelection(pos)
            }
            is MappedAction.MouseClick -> {
                val pos = mouseButtonOptions.indexOfFirst { it.second == action.button }
                if (pos >= 0) spinnerActionValue.setSelection(pos)
            }
            is MappedAction.MouseToggle -> {
                val pos = mouseButtonOptions.indexOfFirst { it.second == action.button }
                if (pos >= 0) spinnerActionValue.setSelection(pos)
            }
            is MappedAction.SwitchLayer -> {
                val pos = layerNames.indexOfFirst { it == action.layerName }
                if (pos >= 0) spinnerActionValue.setSelection(pos)
            }
            else -> {}
        }

        // ===== 子命令管理 =====
        // 子命令 Spinner 列表（动态添加/删除）
        val subCommandSpinners = mutableListOf<Spinner>()

        // 预填充已有子命令
        existingMapping?.subCommands?.forEach { keyCode ->
            val spinner = addSubCommandSpinner(layoutSubCommandList, btnAddSubCommand, subCommandSpinners)
            // 找到对应的键盘按键位置 (+1 因为第一个选项是"无")
            val pos = keyboardKeyOptions.indexOfFirst { it.second == keyCode }
            if (pos >= 0) spinner.setSelection(pos + 1)
        }
        updateAddSubCommandButton(btnAddSubCommand, subCommandSpinners.size)

        // 子命令区域可见性（未设置/切换操作层时隐藏，因为子命令对二者无效）
        layoutSubCommands.visibility =
            if (initialActionType == 0 || initialActionType == 4) View.GONE else View.VISIBLE

        // ===== 动作类型切换监听器 =====
        // 用户切换动作类型时，更新动作值 Spinner 的选项
        spinnerActionType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 重新设置动作值 Spinner 的选项
                setupActionValueSpinner(spinnerActionValue, tvActionLabel, position)
                // 未设置/切换操作层时隐藏子命令区域
                layoutSubCommands.visibility =
                    if (position == 0 || position == 4) View.GONE else View.VISIBLE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 添加子命令按钮点击事件
        btnAddSubCommand.setOnClickListener {
            if (subCommandSpinners.size < KeyMapping.MAX_SUB_COMMANDS) {
                addSubCommandSpinner(layoutSubCommandList, btnAddSubCommand, subCommandSpinners)
                updateAddSubCommandButton(btnAddSubCommand, subCommandSpinners.size)
            }
        }

        // ===== 显示对话框 =====
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val actionType = spinnerActionType.selectedItemPosition
                if (actionType == 0) {
                    // 未设置：取消该按键的映射（回退到公共层/无动作）
                    layer.buttonMappings.remove(button)
                    Log.i(TAG, "Mapping removed: ${buttonDisplayName(button)}")
                } else {
                    // 构建动作（actionType-1 映射回 0=键盘/1=鼠标点击/2=鼠标长按/3=切换层）
                    val action = buildAction(
                        actionType - 1,
                        spinnerActionValue.selectedItemPosition
                    )
                    // 收集子命令（跳过"无"选项）
                    val subCommands = collectSubCommands(subCommandSpinners)

                    // 创建新的按键映射
                    val newMapping = KeyMapping(action, subCommands)

                    // 写入当前操作层的 buttonMappings（MutableMap，可直接修改）
                    layer.buttonMappings[button] = newMapping

                    Log.i(TAG, "Mapping saved: ${buttonDisplayName(button)} → ${newMapping.describe()}")
                }

                // 刷新列表显示
                refreshMappingsList()

                // 保存到运行时和内部存储
                saveProfile(showToast = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示层名称编辑对话框
     *
     * 使用 EditText 输入新名称，保存后重建 profile。
     *
     * ## 主动弹出软键盘
     * AlertDialog 中的 EditText 默认不会自动弹出软键盘，需要：
     * 1. 对话框显示后请求 EditText 焦点
     * 2. 通过 InputMethodManager.showSoftInput 主动弹出键盘
     * 3. 设置 windowSoftInputMode 让对话框窗口自动调整布局
     */
    private fun showLayerNameEditDialog() {
        val layer = currentLayer ?: return

        val editText = EditText(this).apply {
            setText(layer.name)
            setSelection(layer.name.length)
            hint = "输入操作层名称"
            // 单行输入，避免多行模式导致回车键无法确定
            setSingleLine(true)
            // 请求焦点以便接收输入
            requestFocus()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑层名称")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    // 重建操作层（name 是 val，需要 copy）
                    applyLayerInfoChange(name = newName, triggerButton = layer.triggerButton)
                } else {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .create()

        // 对话框窗口显示时主动弹出软键盘
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.show()

        // 对话框显示后再请求一次焦点并弹出软键盘（双保险）
        editText.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 显示触发按键编辑对话框
     *
     * 使用 Spinner 选择触发按键，支持"无"（清除触发按键）。
     * 公共层（Common）不允许设置触发按键。
     */
    private fun showTriggerButtonEditDialog() {
        val layer = currentLayer ?: return
        val profile = this.profile

        // 公共层不能设置触发按键（始终激活，无需触发）
        if (layer === profile.commonLayer) {
            Toast.makeText(this, "公共层不能设置触发按键", Toast.LENGTH_SHORT).show()
            return
        }

        // 构建按键选项: "无" + 所有 ControllerButton 的显示名称
        val buttonOptions = listOf("无") + ControllerButton.values().map { buttonDisplayName(it) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LayerEditActivity,
                android.R.layout.simple_spinner_item,
                buttonOptions
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            // 设置当前选中项
            val currentPos = if (layer.triggerButton != null) {
                1 + ControllerButton.values().indexOf(layer.triggerButton)
            } else 0
            setSelection(currentPos)
        }

        AlertDialog.Builder(this)
            .setTitle("选择触发按键")
            .setMessage("按住此按键激活该操作层，松开回到公共层")
            .setView(spinner)
            .setPositiveButton("确定") { _, _ ->
                val pos = spinner.selectedItemPosition
                val newTrigger = if (pos == 0) null else ControllerButton.values()[pos - 1]
                applyLayerInfoChange(name = layer.name, triggerButton = newTrigger)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ====================================================================
    // 保存方法
    // ====================================================================

    /**
     * 应用操作层信息变更（名称和触发按键）
     *
     * 由于 [OperationLayer.name] 和 [OperationLayer.triggerButton] 是 `val`（不可变），
     * 需要使用 `copy()` 创建新的 OperationLayer，并重建 [ControllerProfile]。
     *
     * ## 重建逻辑
     * - 公共层: 替换 `profile.commonLayer`，触发按键强制为 null
     * - 操作层: 在 `profile.layers` 列表中替换对应项
     *
     * @param name 新的层名称
     * @param triggerButton 新的触发按键（null = 无触发按键）
     */
    private fun applyLayerInfoChange(name: String, triggerButton: ControllerButton?) {
        val oldLayer = currentLayer ?: return
        val oldName = oldLayer.name
        val profile = this.profile

        // 创建新的操作层（copy 保持 buttonMappings 引用不变）
        val newLayer = oldLayer.copy(name = name, triggerButton = triggerButton)

        // 重建 ControllerProfile
        val newProfile: ControllerProfile
        if (oldLayer === profile.commonLayer) {
            // 公共层: 触发按键强制为 null（始终激活）
            newProfile = profile.copy(commonLayer = newLayer.copy(triggerButton = null))
        } else {
            // 操作层: 在列表中替换
            val newLayers = profile.layers.map { if (it === oldLayer) newLayer else it }
            newProfile = profile.copy(layers = newLayers)
        }

        // 层名变化时: 同步更新所有层中引用旧层名的 SwitchLayer 映射
        // 否则重命名后 SwitchLayer(oldName) 会找不到目标层，导致层切换失效
        if (oldName != name) {
            newProfile.allLayers.forEach { layer ->
                layer.buttonMappings.toMutableMap().let { newMap ->
                    var changed = false
                    layer.buttonMappings.forEach { (button, mapping) ->
                        val action = mapping.action
                        if (action is MappedAction.SwitchLayer && action.layerName == oldName) {
                            newMap[button] = mapping.copy(action = MappedAction.SwitchLayer(name))
                            changed = true
                        }
                    }
                    if (changed) {
                        layer.buttonMappings.clear()
                        layer.buttonMappings.putAll(newMap)
                    }
                }
            }
            Log.i(TAG, "Updated SwitchLayer references: $oldName -> $name")
        }

        // 更新本地配置
        this.profile = newProfile

        // 更新当前层引用（指向新对象）
        currentLayer = if (oldLayer === profile.commonLayer) {
            newProfile.commonLayer
        } else {
            newProfile.findLayer(name)
        }

        // 保存（写文件 + 服务运行时同步）
        saveProfile(showToast = false)

        // 刷新操作层 Spinner（层名可能已改变）
        refreshLayerSpinner(newName = name)

        // 刷新列表和按钮显示
        refreshMappingsList()
        updateLayerInfoButtons()

        Log.i(TAG, "Layer info updated: name=$name, trigger=$triggerButton")
        Toast.makeText(this, "层信息已保存", Toast.LENGTH_SHORT).show()
    }

    /**
     * 保存当前配置到文件（服务运行时同步到 SteamInput）
     *
     * - 写回 `steamlike_config.json`（含运行时配置 settings）
     * - 若手柄映射服务在运行，同步 [SteamInput.loadProfile]
     *
     * @param showToast 是否显示保存成功提示
     */
    private fun saveProfile(showToast: Boolean) {
        // 持久化到配置文件（含运行时设置 settings）
        val appConfig = AppConfigStore.load(this)
        File(filesDir, ConfigManager.CONFIG_FILE_NAME)
            .writeText(ControllerConfig.toJson(profile, 2, appConfig))
        // 服务运行时同步（不依赖服务也可正常保存）
        steamInputRef?.let { si -> si.loadProfile(profile) }

        if (showToast) {
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "Profile saved to internal storage")
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    /**
     * 刷新操作层 Spinner
     *
     * 当层名发生变化时，重建 Spinner 的选项列表并选中新的层名。
     * 使用 [suppressLayerSpinnerListener] 阻止程序化更新触发监听器。
     *
     * @param newName 新选中的层名
     */
    private fun refreshLayerSpinner(newName: String) {
        val profile = this.profile
        suppressLayerSpinnerListener = true

        // 重建层名列表
        layerNames = profile.allLayers.map { it.name }

        // 创建新适配器
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, layerNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        layerSpinner.adapter = adapter

        // 选中新的层名
        val pos = layerNames.indexOf(newName).coerceAtLeast(0)
        layerSpinner.setSelection(pos)

        suppressLayerSpinnerListener = false
    }

    /**
     * 根据动作类型设置动作值 Spinner 的选项
     *
     * - 未设置 (type=0): 显示占位选项（保存时取消映射）
     * - 键盘按键 (type=1): 显示键盘按键列表（字母/数字/功能键/修饰键/符号键等）
     * - 鼠标点击 (type=2): 显示 左键/中键/右键
     * - 鼠标长按 (type=3): 显示 左键/中键/右键
     * - 切换操作层 (type=4): 显示所有操作层名称
     *
     * @param spinner 动作值 Spinner
     * @param label 动作值标签（根据类型更新文字）
     * @param actionType 动作类型 (0=未设置, 1=键盘, 2=鼠标点击, 3=鼠标长按, 4=切换层)
     */
    private fun setupActionValueSpinner(spinner: Spinner, label: TextView, actionType: Int) {
        val options: List<String> = when (actionType) {
            0 -> {  // 未设置（取消映射）
                label.text = "未设置（保存后取消该按键映射）"
                listOf("（无）")
            }
            1 -> {  // 键盘按键
                label.text = "选择按键:"
                keyboardKeyOptions.map { it.first }
            }
            2 -> {  // 鼠标点击
                label.text = "选择鼠标按键:"
                mouseButtonOptions.map { it.first }
            }
            3 -> {  // 鼠标长按
                label.text = "选择鼠标按键:"
                mouseButtonOptions.map { it.first }
            }
            4 -> {  // 切换操作层
                label.text = "选择目标层:"
                layerNames
            }
            else -> emptyList()
        }

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    /**
     * 根据动作类型和选中位置构建 [MappedAction]
     *
     * @param actionType 动作类型 (0=键盘, 1=鼠标点击, 2=鼠标长按, 3=切换层)
     * @param valuePosition 动作值 Spinner 的选中位置
     * @return 对应的 MappedAction 实例
     */
    private fun buildAction(actionType: Int, valuePosition: Int): MappedAction {
        return when (actionType) {
            0 -> {  // 键盘按键
                MappedAction.KeyboardKey(keyboardKeyOptions[valuePosition].second)
            }
            1 -> {  // 鼠标点击
                MappedAction.MouseClick(mouseButtonOptions[valuePosition].second)
            }
            2 -> {  // 鼠标长按
                MappedAction.MouseToggle(mouseButtonOptions[valuePosition].second)
            }
            3 -> {  // 切换操作层
                MappedAction.SwitchLayer(layerNames[valuePosition])
            }
            else -> MappedAction.KeyboardKey(keyboardKeyOptions[0].second)
        }
    }

    /**
     * 添加一个子命令选择行
     *
     * 每行包含:
     * - 一个 Spinner（选项: "无" + 键盘按键列表）
     * - 一个删除按钮（✕，移除该子命令行）
     *
     * @param container 子命令行的父容器
     * @param addButton 添加子命令按钮（用于更新其状态）
     * @param spinners 子命令 Spinner 列表（用于跟踪所有行）
     * @return 新创建的 Spinner
     */
    private fun addSubCommandSpinner(
        container: LinearLayout,
        addButton: Button,
        spinners: MutableList<Spinner>
    ): Spinner {
        // 创建水平行容器
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }

        // 创建子命令 Spinner
        val spinner = Spinner(this).apply {
            // 选项: "无" + 键盘按键列表
            val options = listOf("无") + keyboardKeyOptions.map { it.first }
            adapter = ArrayAdapter(
                this@LayerEditActivity,
                android.R.layout.simple_spinner_item,
                options
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            // 占据剩余空间
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(spinner)

        // 创建删除按钮
        val removeButton = Button(this).apply {
            text = "✕"
            setOnClickListener {
                spinners.remove(spinner)
                container.removeView(row)
                updateAddSubCommandButton(addButton, spinners.size)
            }
        }
        row.addView(removeButton)

        // 添加到容器
        container.addView(row)
        spinners.add(spinner)

        return spinner
    }

    /**
     * 更新"添加子命令"按钮的状态和文字
     *
     * @param button 添加子命令按钮
     * @param count 当前子命令数量
     */
    private fun updateAddSubCommandButton(button: Button, count: Int) {
        if (count >= KeyMapping.MAX_SUB_COMMANDS) {
            // 已满，禁用按钮
            button.isEnabled = false
            button.text = "已满 ${KeyMapping.MAX_SUB_COMMANDS} 个"
        } else {
            // 未满，启用按钮并显示计数
            button.isEnabled = true
            button.text = "+ 添加子命令 ($count/${KeyMapping.MAX_SUB_COMMANDS})"
        }
    }

    /**
     * 从子命令 Spinner 列表收集已选择的 KeyCode
     *
     * 跳过选中"无"（位置 0）的 Spinner，只收集有效的键盘按键 KeyCode。
     *
     * @param spinners 子命令 Spinner 列表
     * @return 已选择的 KeyCode 列表（最多3个）
     */
    private fun collectSubCommands(spinners: List<Spinner>): List<Int> {
        return spinners.mapNotNull { spinner ->
            val pos = spinner.selectedItemPosition
            if (pos > 0) {
                // pos=0 是"无"，pos>=1 对应 keyboardKeyOptions[pos-1]
                keyboardKeyOptions[pos - 1].second
            } else {
                null  // 跳过"无"
            }
        }
    }
}
