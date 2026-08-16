package com.steamlike.controller.config

import android.content.Context
import android.net.Uri
import com.steamlike.controller.core.ActionSetLayer
import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerStick
import com.steamlike.controller.core.ControllerTrigger
import com.steamlike.controller.core.SteamInput
import com.steamlike.controller.core.StickOverride
import com.steamlike.controller.core.TriggerOverride
import java.io.File

// ====================================================================
// 配置管理器
// ====================================================================
// ConfigManager 是配置系统的核心类，负责:
// 1. 导出: 从 SteamInput 运行时状态提取配置 → ControllerConfig → JSON
// 2. 导入: JSON → ControllerConfig → 验证并应用到 SteamInput
// 3. 文件 I/O: 内部存储自动加载 + SAF URI 读写
//
// 线程安全:
// - 导出操作是只读的，可在任意线程调用
// - 导入操作会修改 SteamInput 状态，建议在主线程或持有锁时调用
// - 文件 I/O 使用标准 Java IO，无特殊线程要求
// ====================================================================

/**
 * 配置管理器
 *
 * 负责 [SteamInput] 运行时状态与 [ControllerConfig] 配置文件之间的双向转换，
 * 以及配置文件的读写操作。
 *
 * ## 导出流程（SteamInput → JSON 文件）
 * ```
 * SteamInput (运行时状态)
 *      ↓ [exportConfig] 提取所有绑定和属性
 * ControllerConfig (数据模型)
 *      ↓ [toJsonString] 序列化为 JSON
 * JSON 字符串
 *      ↓ [saveToFile] 保存到内部存储 / [saveToUri] 保存到用户选择位置
 * 文件
 * ```
 *
 * ## 导入流程（JSON 文件 → SteamInput）
 * ```
 * 文件
 *      ↓ [loadFromUri] / [loadFromFile] 读取
 * JSON 字符串
 *      ↓ [parseConfig] 解析为数据模型
 * ControllerConfig (数据模型)
 *      ↓ [applyConfig] 验证并应用（跳过无效项）
 * SteamInput (运行时状态更新)
 * ```
 *
 * ## 配置文件位置
 * - **内部配置**: `{context.filesDir}/steamlike_config.json`
 *   - 服务启动时自动加载
 *   - 导入配置后自动保存到此位置
 *   - 删除后恢复为代码中的默认配置
 *
 * - **导出位置**: 由用户通过 SAF（Storage Access Framework）选择
 *   - 可保存到下载目录、外部存储等任意位置
 *   - 用于备份或分享配置
 *
 * ## 使用示例
 * ```kotlin
 * val configManager = ConfigManager(context)
 *
 * // 导出当前配置
 * val config = configManager.exportConfig(steamInput, name = "我的配置")
 * configManager.saveToFile(config)  // 保存到内部存储
 *
 * // 导入配置
 * val loaded = configManager.loadFromFile()
 * if (loaded != null) {
 *     val result = configManager.applyConfig(steamInput, loaded)
 *     println("应用 ${result.appliedCount} 项，跳过 ${result.skippedCount} 项")
 * }
 *
 * // 重置为默认
 * configManager.deleteConfigFile()
 * ```
 *
 * @param context Android Context，用于访问 filesDir 和 ContentResolver
 */
class ConfigManager(private val context: Context) {

    companion object {
        /** 内部配置文件名（存储在 filesDir 中） */
        const val CONFIG_FILE_NAME = "steamlike_config.json"

        /**
         * 获取内部配置文件的 File 对象
         *
         * 路径: `{context.filesDir}/steamlike_config.json`
         * filesDir 是应用私有目录，卸载应用时自动删除。
         *
         * @param context Android Context
         * @return 配置文件 File 对象（可能不存在）
         */
        fun getConfigFile(context: Context): File {
            return File(context.filesDir, CONFIG_FILE_NAME)
        }
    }

    // ====================================================================
    // 导出: SteamInput → ControllerConfig
    // ====================================================================

    /**
     * 从 [SteamInput] 导出当前配置
     *
     * 遍历 SteamInput 的公共层和所有操作层，提取:
     * - 公共层: 按钮绑定、组合键绑定、摇杆/扳机绑定、摇杆/扳机属性
     * - 操作层: 按钮绑定覆盖、摇杆/扳机属性覆盖
     *
     * 导出的配置可以通过 [toJsonString] 序列化为 JSON，再通过 [saveToFile]/[saveToUri] 保存。
     *
     * ## 导出内容
     * ```
     * 公共层:
     *   buttonBindings:    {A: "Jump", B: "Interact", ...}     ← 枚举名 → 动作名
     *   chordBindings:     [{button:"A", action:"TargetEnemy", chord:["RIGHT_SHOULDER"]}]
     *   stickBindings:     {LEFT_STICK: "Move", RIGHT_STICK: "Look"}
     *   triggerBindings:   {LEFT_TRIGGER: "Modifier", RIGHT_TRIGGER: "Cast"}
     *   stickProperties:   {Move: {deadzone:0.2, responseCurve:1.3}, ...}
     *   triggerProperties: {Modifier: {pressThreshold:0.3}, ...}
     *
     * 操作层 (每个层):
     *   buttonBindingOverrides: {A: "Slot5", B: "Slot6", ...}
     *   stickOverrides:         {Look: {deadzone:0.2, responseCurve:0.8}}
     *   triggerOverrides:       {}
     * ```
     *
     * ## 不导出的内容
     * - 动作定义（onPressed/onValueChanged 回调）→ 不可序列化
     * - 输入注入逻辑（哪个动作→哪个键盘按键）→ 在 KeyboardMouseMapper 中定义
     * - 活跃层栈状态 → 运行时状态，不持久化
     *
     * @param steamInput 输入控制器
     * @param name 配置名称（写入 JSON 的 name 字段）
     * @param description 配置描述（写入 JSON 的 description 字段）
     * @return 导出的 [ControllerConfig] 数据
     */
    fun exportConfig(
        steamInput: SteamInput,
        name: String = "导出配置",
        description: String = ""
    ): ControllerConfig {
        val c = steamInput.commonLayer

        // --- 导出公共层绑定 ---

        // 按钮绑定: ControllerButton 枚举 → 枚举名(.name) → 动作名
        // 例: ControllerButton.A → "A" → "Jump"
        val buttonBindings = c.buttonBindings.entries.associate { (btn, action) ->
            btn.name to action
        }

        // 组合键绑定: ChordBinding → ChordBindingConfig
        // 将按钮和修饰键的枚举值转为名称字符串
        val chordBindings = c.chordBindings.map { cb ->
            ChordBindingConfig(
                button = cb.button.name,           // ControllerButton.A → "A"
                action = cb.actionName,             // "TargetEnemy"
                chord = cb.chord.map { it.name }    // [ControllerButton.RIGHT_SHOULDER] → ["RIGHT_SHOULDER"]
            )
        }

        // 摇杆绑定: ControllerStick → 枚举名 → 动作名
        val stickBindings = c.stickBindings.entries.associate { (stick, action) ->
            stick.name to action
        }

        // 扳机绑定: ControllerTrigger → 枚举名 → 动作名
        val triggerBindings = c.triggerBindings.entries.associate { (trigger, action) ->
            trigger.name to action
        }

        // --- 导出公共层属性 ---

        // 摇杆属性: 读取每个摇杆动作的当前 deadzone/responseCurve
        val stickProperties = c.stickActions.entries.associate { (name, action) ->
            name to StickPropertiesConfig(
                deadzone = action.deadzone,
                responseCurve = action.responseCurve
            )
        }

        // 扳机属性: 读取每个扳机动作的当前 pressThreshold
        val triggerProperties = c.triggerActions.entries.associate { (name, action) ->
            name to TriggerPropertiesConfig(
                pressThreshold = action.pressThreshold
            )
        }

        // --- 导出操作层 ---
        // 遍历所有已注册的操作层，导出每个层的覆盖
        val layers = steamInput.actionSetLayers.values.map { layer ->
            exportLayer(layer)
        }

        return ControllerConfig(
            version = ControllerConfig.CURRENT_VERSION,
            name = name,
            description = description,
            commonLayer = CommonLayerConfig(
                buttonBindings = buttonBindings,
                chordBindings = chordBindings,
                stickBindings = stickBindings,
                triggerBindings = triggerBindings,
                stickProperties = stickProperties,
                triggerProperties = triggerProperties
            ),
            layers = layers
        )
    }

    /**
     * 导出单个操作层
     *
     * 将 [ActionSetLayer] 的所有覆盖转换为 [LayerConfig]。
     *
     * @param layer 要导出的操作层
     * @return 操作层配置数据
     */
    private fun exportLayer(layer: ActionSetLayer): LayerConfig {
        // 按钮绑定覆盖: ControllerButton → 枚举名 → 动作名
        val buttonBindingOverrides = layer.buttonBindingOverrides.entries.associate { (btn, action) ->
            btn.name to action
        }

        // 摇杆属性覆盖: StickOverride → StickPropertiesConfig
        // 注意: StickOverride 的属性是 Float?（可空），null 表示"不覆盖该属性"
        val stickOverrides = layer.stickOverrides.entries.associate { (action, override) ->
            action to StickPropertiesConfig(
                deadzone = override.deadzone,           // Float? → Float?
                responseCurve = override.responseCurve   // Float? → Float?
            )
        }

        // 扳机属性覆盖: TriggerOverride → TriggerPropertiesConfig
        val triggerOverrides = layer.triggerOverrides.entries.associate { (action, override) ->
            action to TriggerPropertiesConfig(
                pressThreshold = override.pressThreshold  // Float? → Float?
            )
        }

        return LayerConfig(
            name = layer.name,
            displayName = layer.displayName,
            buttonBindingOverrides = buttonBindingOverrides,
            stickOverrides = stickOverrides,
            triggerOverrides = triggerOverrides
        )
    }

    // ====================================================================
    // 导入: ControllerConfig → SteamInput
    // ====================================================================

    /**
     * 将配置应用到 [SteamInput]
     *
     * 这是导入的核心方法。将 [ControllerConfig] 中的绑定和属性应用到 SteamInput 的运行时状态。
     *
     * ## 重要说明
     *
     * **此方法只修改绑定和属性，不修改动作定义和回调。**
     * - 动作（如 Jump/Interact）必须在代码中预先定义
     * - 配置中引用未定义的动作名会被跳过并记录到 warnings
     * - 导入后所有动作的回调（onPressed/onValueChanged）保持不变
     *
     * ## 应用流程
     *
     * ```
     * 1. 清除公共层现有绑定
     *    (buttonBindings/chordBindings/stickBindings/triggerBindings.clear)
     *
     * 2. 清除所有操作层的覆盖
     *    (buttonBindingOverrides/stickOverrides/triggerOverrides.clear)
     *
     * 3. 应用公共层配置:
     *    a. 按钮绑定     → 验证按钮名+动作名 → bindButton()
     *    b. 组合键绑定   → 验证按钮名+动作名+修饰键名 → addChordBinding()
     *    c. 摇杆绑定     → 验证摇杆名+动作名 → bindStick()
     *    d. 扳机绑定     → 验证扳机名+动作名 → bindTrigger()
     *    e. 摇杆属性     → 验证动作名 → 直接修改 action.deadzone/responseCurve
     *    f. 扳机属性     → 验证动作名 → 直接修改 action.pressThreshold
     *
     * 4. 应用操作层覆盖:
     *    a. 查找/创建操作层 (已有层更新，新层自动创建)
     *    b. 按钮绑定覆盖 → 验证按钮名+动作名 → overrideButtonBinding()
     *    c. 摇杆属性覆盖 → 验证动作名 → overrideStick()
     *    d. 扳机属性覆盖 → 验证动作名 → overrideTrigger()
     * ```
     *
     * ## 验证规则
     * - 按钮名: 必须是有效的 ControllerButton 枚举名（如 "A"、"RIGHT_SHOULDER"）
     * - 摇杆名: 必须是 "LEFT_STICK" 或 "RIGHT_STICK"
     * - 扳机名: 必须是 "LEFT_TRIGGER" 或 "RIGHT_TRIGGER"
     * - 动作名: 必须在公共层中已定义（如 "Jump"、"Slot5"）
     * - 修饰键名: 组合键的 chord 中的每个按钮名也需验证
     *
     * 无效的配置项会被跳过（不中断导入），并记录到返回值的 warnings 列表。
     *
     * @param steamInput 输入控制器
     * @param config 要应用的配置
     * @return [ImportResult] 导入结果（成功数/跳过数/警告列表）
     */
    fun applyConfig(steamInput: SteamInput, config: ControllerConfig): ImportResult {
        val warnings = mutableListOf<String>()
        var appliedCount = 0
        var skippedCount = 0
        val c = steamInput.commonLayer

        // 获取已定义的动作名集合（用于验证配置中引用的动作名）
        // 如果配置引用了不存在的动作名，说明配置与代码不匹配，跳过该项
        val definedButtonActions = c.buttonActions.keys
        val definedStickActions = c.stickActions.keys
        val definedTriggerActions = c.triggerActions.keys

        // ---- 步骤1: 清除公共层现有绑定 ----
        // 先清除旧绑定，再应用新配置，确保配置完全替换而非合并
        c.buttonBindings.clear()
        c.chordBindings.clear()
        c.stickBindings.clear()
        c.triggerBindings.clear()

        // ---- 步骤2: 清除所有操作层的覆盖 ----
        // 操作层本身不删除（保留层定义），只清除覆盖内容
        steamInput.actionSetLayers.values.forEach { layer ->
            layer.buttonBindingOverrides.clear()
            layer.stickOverrides.clear()
            layer.triggerOverrides.clear()
        }

        // ---- 步骤3: 应用公共层配置 ----

        // 3a. 按钮绑定
        config.commonLayer.buttonBindings.forEach { (btnName, actionName) ->
            // 验证按钮名: "A" → ControllerButton.A
            val button = parseButton(btnName)
            if (button == null) {
                warnings.add("未知按钮: $btnName")
                skippedCount++
                return@forEach
            }
            // 验证动作名: 必须在 definedButtonActions 中
            if (actionName !in definedButtonActions) {
                warnings.add("按钮 $btnName → 未定义动作: $actionName")
                skippedCount++
                return@forEach
            }
            steamInput.bindButton(button, actionName)
            appliedCount++
        }

        // 3b. 组合键绑定
        config.commonLayer.chordBindings.forEach { cb ->
            // 验证触发按钮名
            val button = parseButton(cb.button)
            if (button == null) {
                warnings.add("组合键: 未知按钮 ${cb.button}")
                skippedCount++
                return@forEach
            }
            // 验证动作名
            if (cb.action !in definedButtonActions) {
                warnings.add("组合键 ${cb.button}: 未定义动作 ${cb.action}")
                skippedCount++
                return@forEach
            }
            // 验证修饰键名列表，逐个解析
            val chordButtons = cb.chord.mapNotNull { name ->
                val btn = parseButton(name)
                if (btn == null) {
                    warnings.add("组合键 ${cb.button}: 未知修饰按钮 $name")
                    null  // 跳过无效的修饰键
                } else btn
            }.toSet()
            // 如果有修饰键解析失败，跳过整个组合键绑定
            if (chordButtons.size != cb.chord.size) {
                skippedCount++
                return@forEach
            }
            c.addChordBinding(button, cb.action, chordButtons)
            appliedCount++
        }

        // 3c. 摇杆绑定
        config.commonLayer.stickBindings.forEach { (stickName, actionName) ->
            val stick = parseStick(stickName)
            if (stick == null) {
                warnings.add("未知摇杆: $stickName")
                skippedCount++
                return@forEach
            }
            if (actionName !in definedStickActions) {
                warnings.add("摇杆 $stickName → 未定义动作: $actionName")
                skippedCount++
                return@forEach
            }
            steamInput.bindStick(stick, actionName)
            appliedCount++
        }

        // 3d. 扳机绑定
        config.commonLayer.triggerBindings.forEach { (triggerName, actionName) ->
            val trigger = parseTrigger(triggerName)
            if (trigger == null) {
                warnings.add("未知扳机: $triggerName")
                skippedCount++
                return@forEach
            }
            if (actionName !in definedTriggerActions) {
                warnings.add("扳机 $triggerName → 未定义动作: $actionName")
                skippedCount++
                return@forEach
            }
            steamInput.bindTrigger(trigger, actionName)
            appliedCount++
        }

        // 3e. 摇杆属性（直接修改动作的属性值）
        // 注意: 属性不需要"绑定"，因为属性是动作本身的参数
        config.commonLayer.stickProperties.forEach { (actionName, props) ->
            val action = c.stickActions[actionName]
            if (action == null) {
                warnings.add("摇杆属性: 未定义动作 $actionName")
                skippedCount++
                return@forEach
            }
            // 只覆盖非 null 的属性（null 表示"使用默认值"）
            props.deadzone?.let { action.deadzone = it }
            props.responseCurve?.let { action.responseCurve = it }
            appliedCount++
        }

        // 3f. 扳机属性
        config.commonLayer.triggerProperties.forEach { (actionName, props) ->
            val action = c.triggerActions[actionName]
            if (action == null) {
                warnings.add("扳机属性: 未定义动作 $actionName")
                skippedCount++
                return@forEach
            }
            props.pressThreshold?.let { action.pressThreshold = it }
            appliedCount++
        }

        // ---- 步骤4: 应用操作层覆盖 ----

        config.layers.forEach { layerConfig ->
            // 查找已有层，不存在则创建新层
            // 这允许配置文件定义代码中不存在的自定义层
            val layer = steamInput.actionSetLayers[layerConfig.name]
                ?: steamInput.createActionSetLayer(layerConfig.name, layerConfig.displayName)

            // 4a. 按钮绑定覆盖
            layerConfig.buttonBindingOverrides.forEach { (btnName, actionName) ->
                val button = parseButton(btnName)
                if (button == null) {
                    warnings.add("层 ${layerConfig.name}: 未知按钮 $btnName")
                    skippedCount++
                    return@forEach
                }
                if (actionName !in definedButtonActions) {
                    warnings.add("层 ${layerConfig.name}: 未定义动作 $actionName")
                    skippedCount++
                    return@forEach
                }
                layer.overrideButtonBinding(button, actionName)
                appliedCount++
            }

            // 4b. 摇杆属性覆盖
            // 使用 overrideStick 的 block 语法，将配置值应用到 StickOverride
            layerConfig.stickOverrides.forEach { (actionName, props) ->
                if (actionName !in definedStickActions) {
                    warnings.add("层 ${layerConfig.name}: 未定义摇杆动作 $actionName")
                    skippedCount++
                    return@forEach
                }
                layer.overrideStick(actionName) {
                    // this = StickOverride，只设置非 null 的属性
                    props.deadzone?.let { this.deadzone = it }
                    props.responseCurve?.let { this.responseCurve = it }
                }
                appliedCount++
            }

            // 4c. 扳机属性覆盖
            layerConfig.triggerOverrides.forEach { (actionName, props) ->
                if (actionName !in definedTriggerActions) {
                    warnings.add("层 ${layerConfig.name}: 未定义扳机动作 $actionName")
                    skippedCount++
                    return@forEach
                }
                layer.overrideTrigger(actionName) {
                    props.pressThreshold?.let { this.pressThreshold = it }
                }
                appliedCount++
            }
        }

        return ImportResult(
            appliedCount = appliedCount,
            skippedCount = skippedCount,
            warnings = warnings
        )
    }

    // ====================================================================
    // 文件 I/O: 内部存储
    // ====================================================================

    /**
     * 保存配置到内部存储
     *
     * 配置以 JSON 格式（UTF-8编码，缩进2空格）保存到:
     * `{context.filesDir}/steamlike_config.json`
     *
     * 此文件在服务启动时由 [loadFromFile] 自动加载。
     *
     * @param config 要保存的配置
     * @return 保存的文件 File 对象
     */
    fun saveToFile(config: ControllerConfig): File {
        val file = getConfigFile(context)
        file.writeText(config.toJsonString(2), Charsets.UTF_8)
        return file
    }

    /**
     * 从内部存储加载配置
     *
     * 读取 `{filesDir}/steamlike_config.json` 并解析为 [ControllerConfig]。
     *
     * @return 配置数据；文件不存在或解析失败返回 null
     */
    fun loadFromFile(): ControllerConfig? {
        val file = getConfigFile(context)
        if (!file.exists()) return null
        return try {
            parseConfig(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // JSON 格式错误时返回 null，使用默认配置
            null
        }
    }

    /**
     * 删除内部配置文件
     *
     * 删除后服务重启时会使用代码中定义的默认配置。
     * 用于"重置为默认配置"功能。
     *
     * @return true=删除成功（或文件原本就不存在），false=删除失败
     */
    fun deleteConfigFile(): Boolean {
        return getConfigFile(context).delete()
    }

    /**
     * 检查内部配置文件是否存在
     *
     * 用于 UI 显示配置状态（已加载/未加载）。
     *
     * @return true=配置文件存在
     */
    fun hasConfigFile(): Boolean {
        return getConfigFile(context).exists()
    }

    // ====================================================================
    // 文件 I/O: SAF（Storage Access Framework）
    // ====================================================================
    // SAF 允许用户通过系统文件选择器选择文件位置，
    // 无需请求 STORAGE 权限，符合 Android 安全策略。
    // 使用 ContentResolver 通过 content:// URI 读写文件。
    // ====================================================================

    /**
     * 将配置导出到用户指定的 URI
     *
     * 通过 SAF（ACTION_CREATE_DOCUMENT）获取的 URI 写入配置。
     * 用于"导出配置"功能，用户可选择保存位置。
     *
     * ## 使用流程
     * ```kotlin
     * // 1. 启动 SAF 创建文档
     * val launcher = registerForActivityResult(
     *     ActivityResultContracts.CreateDocument("application/json")
     * ) { uri ->
     *     if (uri != null) {
     *         // 2. 写入配置
     *         configManager.saveToUri(config, uri)
     *     }
     * }
     * launcher.launch("steamlike_config.json")
     * ```
     *
     * @param config 要导出的配置
     * @param uri 目标 URI（来自 SAF ACTION_CREATE_DOCUMENT）
     */
    fun saveToUri(config: ControllerConfig, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(config.toJsonString(2).toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * 从用户指定的 URI 导入配置
     *
     * 通过 SAF（ACTION_OPEN_DOCUMENT）获取的 URI 读取配置。
     * 用于"导入配置"功能，用户可选择要加载的配置文件。
     *
     * ## 使用流程
     * ```kotlin
     * // 1. 启动 SAF 打开文档
     * val launcher = registerForActivityResult(
     *     ActivityResultContracts.OpenDocument()
     * ) { uri ->
     *     if (uri != null) {
     *         // 2. 读取配置
     *         val config = configManager.loadFromUri(uri)
     *         if (config != null) {
     *             // 3. 应用配置
     *             configManager.applyConfig(steamInput, config)
     *         }
     *     }
     * }
     * launcher.launch(arrayOf("application/json", "text/plain"))
     * ```
     *
     * @param uri 源 URI（来自 SAF ACTION_OPEN_DOCUMENT）
     * @return 配置数据；读取失败或解析失败返回 null
     */
    fun loadFromUri(uri: Uri): ControllerConfig? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val json = input.bufferedReader(Charsets.UTF_8).readText()
                parseConfig(json)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 URI 导入配置并保存到内部存储
     *
     * 便捷方法，等效于 [loadFromUri] + [saveToFile]。
     * 导入后配置会自动持久化，下次启动自动加载。
     *
     * @param uri 源 URI
     * @return 导入的配置；读取失败返回 null
     */
    fun importFromUri(uri: Uri): ControllerConfig? {
        val config = loadFromUri(uri) ?: return null
        saveToFile(config)
        return config
    }
}

/**
 * 配置导入结果
 *
 * 由 [ConfigManager.applyConfig] 返回，包含导入的统计信息和警告。
 *
 * ## 使用示例
 * ```kotlin
 * val result = configManager.applyConfig(steamInput, config)
 * if (result.isSuccess) {
 *     println("导入成功: ${result.appliedCount} 项")
 * } else {
 *     println("导入完成但有跳过: ${result.appliedCount} 成功, ${result.skippedCount} 跳过")
 *     result.warnings.forEach { println("  警告: $it") }
 * }
 * ```
 *
 * @param appliedCount 成功应用的配置项数量
 * @param skippedCount 因无效数据被跳过的数量
 * @param warnings 警告信息列表（如"未知按钮: Xxx"、"未定义动作: Yyy"）
 */
data class ImportResult(
    val appliedCount: Int,
    val skippedCount: Int,
    val warnings: List<String>
) {
    /** 是否有警告信息 */
    val hasWarnings: Boolean get() = warnings.isNotEmpty()

    /** 导入是否完全成功（无跳过项） */
    val isSuccess: Boolean get() = skippedCount == 0
}
