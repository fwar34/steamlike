package com.steamlike.controller.config

import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerStick
import com.steamlike.controller.core.ControllerTrigger
import org.json.JSONArray
import org.json.JSONObject

// ====================================================================
// 配置数据模型
// ====================================================================
// 本文件定义配置文件的数据模型，以及与 JSON 之间的双向转换。
//
// 设计原则:
// 1. 数据类只存储"绑定关系"和"属性值"，不包含可执行逻辑（回调/lambda）
// 2. 所有字段使用 String 引用枚举名（如 "A"、"RIGHT_SHOULDER"），而非枚举值本身
//    → 这样配置文件可直接序列化为 JSON，且人类可读
// 3. 属性值使用 Float?（可空），null 表示"不覆盖该属性，使用默认值"
// 4. JSON 转换使用 Android 内置的 org.json，无需额外依赖
// ====================================================================

/**
 * 控制器配置文件数据模型（根对象）
 *
 * 对应一个完整的 JSON 配置文件，包含公共层配置和所有操作层配置。
 *
 * ## JSON 格式示例
 * ```json
 * {
 *   "version": 1,
 *   "name": "WoW默认配置",
 *   "description": "WoW乌龟服1.18.1默认按键映射",
 *   "commonLayer": {
 *     "buttonBindings": { "A": "Jump", "B": "Interact" },
 *     "chordBindings": [
 *       { "button": "A", "action": "TargetEnemy", "chord": ["RIGHT_SHOULDER"] }
 *     ],
 *     "stickBindings": { "LEFT_STICK": "Move" },
 *     "triggerBindings": { "LEFT_TRIGGER": "Modifier" },
 *     "stickProperties": {
 *       "Move": { "deadzone": 0.2, "responseCurve": 1.3 }
 *     },
 *     "triggerProperties": {
 *       "Modifier": { "pressThreshold": 0.3 }
 *     }
 *   },
 *   "layers": [
 *     {
 *       "name": "Combat",
 *       "displayName": "战斗模式",
 *       "buttonBindingOverrides": { "A": "Slot5" },
 *       "stickOverrides": {
 *         "Look": { "deadzone": 0.2, "responseCurve": 0.8 }
 *       },
 *       "triggerOverrides": {}
 *     }
 *   ]
 * }
 * ```
 *
 * ## 设计说明
 *
 * ### 配置文件包含什么？
 * - **按钮绑定**: 哪个按钮 → 哪个动作（如 A → Jump）
 * - **组合键绑定**: 按钮 + 修饰键 → 动作（如 A + RB → TargetEnemy）
 * - **摇杆/扳机绑定**: 哪个摇杆/扳机 → 哪个动作
 * - **摇杆/扳机属性**: 死区、响应曲线、按压阈值等数值
 * - **操作层覆盖**: 每个层的绑定覆盖和属性覆盖
 *
 * ### 配置文件不包含什么？
 * - **动作定义**: 动作（如 Jump）的创建和属性初始化在代码中完成
 * - **回调函数**: onPressed/onValueChanged/onDirectionChanged 等 lambda 无法序列化
 * - **输入注入逻辑**: 哪个动作触发哪个键盘/鼠标事件，在 KeyboardMouseMapper 中定义
 *
 * ### 为什么这样设计？
 * 配置文件只修改"绑定关系"，不修改"动作行为"。
 * 导入配置后，所有动作的回调保持不变，只是"哪个按钮触发哪个动作"改变了。
 * 这使得用户可以安全地自定义按键布局，而不会破坏功能逻辑。
 *
 * @param version 配置文件版本号（用于未来版本迁移，当前=1）
 * @param name 配置名称（用于识别）
 * @param description 配置描述（可选）
 * @param commonLayer 公共层配置（所有操作层共享的基础绑定）
 * @param layers 操作层配置列表（每个层可覆盖公共层的部分绑定）
 */
data class ControllerConfig(
    val version: Int = CURRENT_VERSION,
    val name: String = "",
    val description: String = "",
    val commonLayer: CommonLayerConfig = CommonLayerConfig(),
    val layers: List<LayerConfig> = emptyList()
) {
    companion object {
        /**
         * 当前配置文件版本号
         *
         * 版本号用于未来配置文件格式变更时的兼容性处理。
         * 导入时如果版本号不匹配，可执行迁移逻辑。
         */
        const val CURRENT_VERSION = 1
    }
}

/**
 * 公共层配置
 *
 * 公共层是所有操作层的基础，定义了所有可能的按钮/摇杆/扳机绑定。
 * 操作层通过覆盖（overrides）来修改公共层的部分绑定。
 *
 * ## 字段说明
 *
 * ### 绑定（Bindings）: "哪个硬件输入 → 哪个动作"
 * - [buttonBindings]: 按钮绑定，如 `"A" → "Jump"` 表示按 A 键触发 Jump 动作
 * - [stickBindings]: 摇杆绑定，如 `"LEFT_STICK" → "Move"` 表示左摇杆控制 Move 动作
 * - [triggerBindings]: 扳机绑定，如 `"RIGHT_TRIGGER" → "Cast"` 表示右扳机触发 Cast 动作
 * - [chordBindings]: 组合键绑定列表，如 `A + RB → TargetEnemy`
 *
 * ### 属性（Properties）: 动作的数值参数
 * - [stickProperties]: 摇杆动作的属性，如死区和响应曲线
 * - [triggerProperties]: 扳机动作的属性，如按压阈值
 *
 * ## 绑定与属性的关系
 * 绑定决定"哪个硬件输入触发哪个动作"，属性决定"动作如何响应输入"。
 * 例如：`LEFT_STICK → Move`（绑定）+ `Move.deadzone = 0.2`（属性）
 *
 * @param buttonBindings 按钮绑定: 按钮枚举名(String) → 动作名(String)
 * @param chordBindings 组合键绑定列表
 * @param stickBindings 摇杆绑定: 摇杆枚举名(String) → 动作名(String)
 * @param triggerBindings 扳机绑定: 扳机枚举名(String) → 动作名(String)
 * @param stickProperties 摇杆属性: 动作名(String) → 属性配置
 * @param triggerProperties 扳机属性: 动作名(String) → 属性配置
 */
data class CommonLayerConfig(
    val buttonBindings: Map<String, String> = emptyMap(),
    val chordBindings: List<ChordBindingConfig> = emptyList(),
    val stickBindings: Map<String, String> = emptyMap(),
    val triggerBindings: Map<String, String> = emptyMap(),
    val stickProperties: Map<String, StickPropertiesConfig> = emptyMap(),
    val triggerProperties: Map<String, TriggerPropertiesConfig> = emptyMap()
)

/**
 * 组合键绑定配置
 *
 * 参考 Steam Input 的 Sub-Command 机制：同一按钮在不同修饰键状态下触发不同动作。
 *
 * ## 示例
 * ```
 * A 单独按下     → Jump       (普通绑定)
 * A + RB 按住    → TargetEnemy (组合键绑定)
 * A + RB + LT   → Potion      (更具体的组合键优先)
 * ```
 *
 * ## 匹配规则
 * 当按钮按下时，SteamInput 从所有匹配的组合键绑定中，
 * 选择 `chord` 是当前 heldButtons 子集且 chordSize 最大的那个。
 *
 * @param button 触发按钮的枚举名（如 "A"、"DPAD_UP"）
 * @param action 触发的动作名（如 "TargetEnemy"）
 * @param chord 修饰按钮的枚举名列表（如 ["RIGHT_SHOULDER"]），空列表=普通绑定
 */
data class ChordBindingConfig(
    val button: String,
    val action: String,
    val chord: List<String> = emptyList()
)

/**
 * 摇杆属性配置
 *
 * 定义摇杆动作的响应参数。`null` 表示不设置该属性（使用代码中的默认值）。
 *
 * ## 属性说明
 * - **deadzone（死区）**: 摇杆中心附近的忽略范围（0.0~1.0）
 *   - 值越大，中心区域越不敏感（避免误触）
 *   - 典型值: 0.15~0.25
 *
 * - **responseCurve（响应曲线）**: 摇杆输入的非线性映射指数
 *   - >1.0: 前半段更慢，适合精细瞄准
 *   - <1.0: 前半段更快，适合快速移动
 *   - =1.0: 线性响应
 *   - 典型值: 0.5~1.5
 *
 * @param deadzone 死区（null=使用默认值）
 * @param responseCurve 响应曲线指数（null=使用默认值）
 */
data class StickPropertiesConfig(
    val deadzone: Float? = null,
    val responseCurve: Float? = null
)

/**
 * 扳机属性配置
 *
 * 定义扳机动作的响应参数。`null` 表示不设置该属性（使用代码中的默认值）。
 *
 * ## 属性说明
 * - **pressThreshold（按压阈值）**: 扳机从"未按下"到"按下"的阈值（0.0~1.0）
 *   - 值越小，轻触即触发（适合快速操作）
 *   - 值越大，需要更用力按压（避免误触）
 *   - 典型值: 0.3
 *
 * @param pressThreshold 按压阈值（null=使用默认值）
 */
data class TriggerPropertiesConfig(
    val pressThreshold: Float? = null
)

/**
 * 操作层配置
 *
 * 操作层在公共层基础上做增量覆盖，只修改需要变更的部分。
 * 未覆盖的按键/属性继续使用公共层的默认值。
 *
 * ## 覆盖类型
 * 1. **绑定覆盖** ([buttonBindingOverrides]): 改变"哪个按钮→哪个动作"
 *    - 例如: Combat层将 A 从 "Jump" 改为 "Slot5"
 *
 * 2. **属性覆盖** ([stickOverrides]/[triggerOverrides]): 修改动作的属性值
 *    - 例如: Aim层将 "Look" 摇杆的死区从 0.15 改为 0.25
 *
 * ## 多层叠加
 * 多个操作层可同时激活，按激活顺序形成栈。
 * 查找绑定时从栈顶到栈底遍历，第一个找到的覆盖生效。
 *
 * @param name 层标识名（如 "Combat"），必须唯一
 * @param displayName 显示名（如 "战斗模式"），用于UI显示
 * @param buttonBindingOverrides 按钮绑定覆盖: 按钮枚举名 → 动作名
 * @param stickOverrides 摇杆属性覆盖: 动作名 → 属性配置
 * @param triggerOverrides 扳机属性覆盖: 动作名 → 属性配置
 */
data class LayerConfig(
    val name: String,
    val displayName: String = name,
    val buttonBindingOverrides: Map<String, String> = emptyMap(),
    val stickOverrides: Map<String, StickPropertiesConfig> = emptyMap(),
    val triggerOverrides: Map<String, TriggerPropertiesConfig> = emptyMap()
)

// ====================================================================
// JSON 序列化: 数据模型 → JSON
// ====================================================================
// 以下扩展函数将数据模型转换为 org.json.JSONObject / JSONArray。
// 转换规则:
// - Map<String, String> → JSONObject (key→value)
// - List<T> → JSONArray
// - Float? → 只在非 null 时写入 JSON（null 属性不写入，节省空间）
// - 枚举名直接作为 String 值
// ====================================================================

/**
 * 将 [ControllerConfig] 转换为格式化的 JSON 字符串
 *
 * @param indent 缩进空格数（0=紧凑模式，无换行无缩进；2=标准格式化）
 * @return JSON 字符串（UTF-8编码）
 *
 * 示例:
 * ```kotlin
 * val json = config.toJsonString(2)  // 格式化输出，缩进2空格
 * val json = config.toJsonString(0)  // 紧凑输出，适合网络传输
 * ```
 */
fun ControllerConfig.toJsonString(indent: Int = 2): String {
    return toJsonObject().toString(indent)
}

/**
 * 将 [ControllerConfig] 转换为 [JSONObject]
 *
 * 这是序列化的核心函数，递归调用各子对象的 toJsonObject() 方法。
 * 用于内部转换，外部通常调用 [toJsonString] 获取字符串。
 */
fun ControllerConfig.toJsonObject(): JSONObject {
    return JSONObject().apply {
        put("version", version)
        put("name", name)
        put("description", description)
        put("commonLayer", commonLayer.toJsonObject())
        put("layers", JSONArray().apply { layers.forEach { put(it.toJsonObject()) } })
    }
}

/**
 * 公共层配置 → JSONObject
 *
 * 转换所有绑定映射和属性映射。
 */
private fun CommonLayerConfig.toJsonObject(): JSONObject {
    return JSONObject().apply {
        // 绑定: Map<String,String> → JSONObject
        put("buttonBindings", buttonBindings.toJsonObject())
        // 组合键: List<ChordBindingConfig> → JSONArray
        put("chordBindings", JSONArray().apply {
            chordBindings.forEach { put(it.toJsonObject()) }
        })
        put("stickBindings", stickBindings.toJsonObject())
        put("triggerBindings", triggerBindings.toJsonObject())
        // 属性: Map<String, StickPropertiesConfig> → JSONObject (key=动作名)
        put("stickProperties", JSONObject().apply {
            stickProperties.forEach { (action, props) -> put(action, props.toJsonObject()) }
        })
        put("triggerProperties", JSONObject().apply {
            triggerProperties.forEach { (action, props) -> put(action, props.toJsonObject()) }
        })
    }
}

/**
 * 组合键绑定 → JSONObject
 *
 * 转换 button/action/chord 三个字段。
 * chord 列表转换为 JSONArray。
 */
private fun ChordBindingConfig.toJsonObject(): JSONObject {
    return JSONObject().apply {
        put("button", button)
        put("action", action)
        put("chord", JSONArray().apply { chord.forEach { put(it) } })
    }
}

/**
 * 摇杆属性 → JSONObject
 *
 * 只写入非 null 的属性。null 属性不写入 JSON，
 * 解析时也会返回 null（表示"使用默认值"）。
 */
private fun StickPropertiesConfig.toJsonObject(): JSONObject {
    return JSONObject().apply {
        deadzone?.let { put("deadzone", it.toDouble()) }
        responseCurve?.let { put("responseCurve", it.toDouble()) }
    }
}

/**
 * 扳机属性 → JSONObject
 *
 * 只写入非 null 的属性。
 * 注意: Float 转为 Double 写入 JSON（JSON 标准浮点类型）。
 */
private fun TriggerPropertiesConfig.toJsonObject(): JSONObject {
    return JSONObject().apply {
        pressThreshold?.let { put("pressThreshold", it.toDouble()) }
    }
}

/**
 * 操作层配置 → JSONObject
 *
 * 转换层名、显示名和所有覆盖映射。
 */
private fun LayerConfig.toJsonObject(): JSONObject {
    return JSONObject().apply {
        put("name", name)
        put("displayName", displayName)
        put("buttonBindingOverrides", buttonBindingOverrides.toJsonObject())
        put("stickOverrides", JSONObject().apply {
            stickOverrides.forEach { (action, props) -> put(action, props.toJsonObject()) }
        })
        put("triggerOverrides", JSONObject().apply {
            triggerOverrides.forEach { (action, props) -> put(action, props.toJsonObject()) }
        })
    }
}

/**
 * Map<String, String> → JSONObject
 *
 * 工具函数: 将字符串映射转为 JSON 对象。
 * 用于 buttonBindings/stickBindings/triggerBindings/buttonBindingOverrides。
 */
private fun Map<String, String>.toJsonObject(): JSONObject {
    return JSONObject().apply { forEach { (k, v) -> put(k, v) } }
}

// ====================================================================
// JSON 反序列化: JSON → 数据模型
// ====================================================================
// 以下函数从 JSON 字符串/对象解析为数据模型。
// 解析规则:
// - 使用 optXxx 方法，缺失字段返回默认值（不会抛异常）
// - 必需字段使用 getXxx 方法，缺失会抛 JSONException
// - Float 属性: 检查 has() 后再读取，不存在则返回 null
// - 枚举名: 原样保留为 String，后续在 ConfigManager 中验证
// ====================================================================

/**
 * 从 JSON 字符串解析 [ControllerConfig]
 *
 * @param jsonStr JSON 字符串
 * @return 解析后的配置
 * @throws org.json.JSONException 如果 JSON 格式错误或必需字段缺失
 *
 * 示例:
 * ```kotlin
 * val config = parseConfig("""{"version":1,"name":"test",...}""")
 * ```
 */
fun parseConfig(jsonStr: String): ControllerConfig {
    return parseConfig(JSONObject(jsonStr))
}

/**
 * 从 [JSONObject] 解析 [ControllerConfig]
 *
 * 使用 optXxx 方法容忍字段缺失，使用默认值填充。
 * 版本号缺失时默认为 [ControllerConfig.CURRENT_VERSION]。
 */
fun parseConfig(json: JSONObject): ControllerConfig {
    return ControllerConfig(
        version = json.optInt("version", ControllerConfig.CURRENT_VERSION),
        name = json.optString("name", ""),
        description = json.optString("description", ""),
        commonLayer = parseCommonLayer(json.optJSONObject("commonLayer")),
        layers = parseLayers(json.optJSONArray("layers"))
    )
}

/**
 * 解析公共层配置
 *
 * 如果 JSON 对象为 null（字段缺失），返回空的默认配置。
 */
private fun parseCommonLayer(json: JSONObject?): CommonLayerConfig {
    if (json == null) return CommonLayerConfig()
    return CommonLayerConfig(
        buttonBindings = parseStringMap(json.optJSONObject("buttonBindings")),
        chordBindings = parseChordBindings(json.optJSONArray("chordBindings")),
        stickBindings = parseStringMap(json.optJSONObject("stickBindings")),
        triggerBindings = parseStringMap(json.optJSONObject("triggerBindings")),
        stickProperties = parseStickProperties(json.optJSONObject("stickProperties")),
        triggerProperties = parseTriggerProperties(json.optJSONObject("triggerProperties"))
    )
}

/**
 * 解析 String→String 映射
 *
 * 用于 buttonBindings/stickBindings/triggerBindings/buttonBindingOverrides。
 * 遍历 JSON 对象的所有 key，读取对应的 String 值。
 */
private fun parseStringMap(json: JSONObject?): Map<String, String> {
    if (json == null) return emptyMap()
    val result = mutableMapOf<String, String>()
    json.keys().forEach { key -> result[key] = json.getString(key) }
    return result
}

/**
 * 解析组合键绑定列表
 *
 * 遍历 JSON 数组，解析每个组合键绑定对象。
 * chord 字段为字符串数组，逐个读取。
 */
private fun parseChordBindings(json: JSONArray?): List<ChordBindingConfig> {
    if (json == null) return emptyList()
    val result = mutableListOf<ChordBindingConfig>()
    for (i in 0 until json.length()) {
        val obj = json.getJSONObject(i)
        // 解析修饰键列表
        val chordArray = obj.optJSONArray("chord")
        val chord = mutableListOf<String>()
        if (chordArray != null) {
            for (j in 0 until chordArray.length()) {
                chord.add(chordArray.getString(j))
            }
        }
        result.add(ChordBindingConfig(
            button = obj.getString("button"),    // 必需字段
            action = obj.getString("action"),    // 必需字段
            chord = chord
        ))
    }
    return result
}

/**
 * 解析摇杆属性映射
 *
 * key = 动作名, value = {deadzone, responseCurve}
 * 属性不存在时返回 null（表示"使用默认值"）。
 */
private fun parseStickProperties(json: JSONObject?): Map<String, StickPropertiesConfig> {
    if (json == null) return emptyMap()
    val result = mutableMapOf<String, StickPropertiesConfig>()
    json.keys().forEach { action ->
        val obj = json.getJSONObject(action)
        result[action] = StickPropertiesConfig(
            // 使用 has() 检查字段是否存在，不存在则返回 null
            deadzone = if (obj.has("deadzone")) obj.getDouble("deadzone").toFloat() else null,
            responseCurve = if (obj.has("responseCurve")) obj.getDouble("responseCurve").toFloat() else null
        )
    }
    return result
}

/**
 * 解析扳机属性映射
 *
 * key = 动作名, value = {pressThreshold}
 * 属性不存在时返回 null。
 */
private fun parseTriggerProperties(json: JSONObject?): Map<String, TriggerPropertiesConfig> {
    if (json == null) return emptyMap()
    val result = mutableMapOf<String, TriggerPropertiesConfig>()
    json.keys().forEach { action ->
        val obj = json.getJSONObject(action)
        result[action] = TriggerPropertiesConfig(
            pressThreshold = if (obj.has("pressThreshold")) obj.getDouble("pressThreshold").toFloat() else null
        )
    }
    return result
}

/**
 * 解析操作层列表
 *
 * 遍历 JSON 数组，解析每个操作层配置。
 * displayName 缺失时使用 name 作为默认值。
 */
private fun parseLayers(json: JSONArray?): List<LayerConfig> {
    if (json == null) return emptyList()
    val result = mutableListOf<LayerConfig>()
    for (i in 0 until json.length()) {
        val obj = json.getJSONObject(i)
        result.add(LayerConfig(
            name = obj.getString("name"),    // 必需字段
            displayName = obj.optString("displayName", obj.getString("name")),
            buttonBindingOverrides = parseStringMap(obj.optJSONObject("buttonBindingOverrides")),
            stickOverrides = parseStickProperties(obj.optJSONObject("stickOverrides")),
            triggerOverrides = parseTriggerProperties(obj.optJSONObject("triggerOverrides"))
        ))
    }
    return result
}

// ====================================================================
// 枚举名称转换工具
// ====================================================================
// 配置文件中使用枚举名（String）引用按钮/摇杆/扳机，
// 导入时需要转换回 Kotlin 枚举值。
// 使用 valueOf() 进行转换，无效名称返回 null（而非抛异常）。
// ====================================================================

/**
 * 将按钮名称字符串转为 [ControllerButton] 枚举
 *
 * @param name 按钮枚举名（如 "A"、"RIGHT_SHOULDER"、"DPAD_UP"）
 * @return 对应的枚举值，无效名称返回 null
 *
 * 示例:
 * ```kotlin
 * parseButton("A")               // → ControllerButton.A
 * parseButton("RIGHT_SHOULDER")  // → ControllerButton.RIGHT_SHOULDER
 * parseButton("Invalid")         // → null
 * ```
 */
fun parseButton(name: String): ControllerButton? {
    return try { ControllerButton.valueOf(name) } catch (e: IllegalArgumentException) { null }
}

/**
 * 将摇杆名称字符串转为 [ControllerStick] 枚举
 *
 * @param name 摇杆枚举名（"LEFT_STICK" 或 "RIGHT_STICK"）
 * @return 对应的枚举值，无效名称返回 null
 */
fun parseStick(name: String): ControllerStick? {
    return try { ControllerStick.valueOf(name) } catch (e: IllegalArgumentException) { null }
}

/**
 * 将扳机名称字符串转为 [ControllerTrigger] 枚举
 *
 * @param name 扳机枚举名（"LEFT_TRIGGER" 或 "RIGHT_TRIGGER"）
 * @return 对应的枚举值，无效名称返回 null
 */
fun parseTrigger(name: String): ControllerTrigger? {
    return try { ControllerTrigger.valueOf(name) } catch (e: IllegalArgumentException) { null }
}
