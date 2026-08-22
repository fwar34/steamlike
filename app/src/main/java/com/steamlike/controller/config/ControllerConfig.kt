package com.steamlike.controller.config

import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerProfile
import com.steamlike.controller.core.GlobalSettings
import com.steamlike.controller.core.KeyMapping
import com.steamlike.controller.core.MappedAction
import com.steamlike.controller.core.MouseButton
import com.steamlike.controller.core.OperationLayer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 控制器配置文件 JSON 序列化/反序列化
 *
 * ## JSON 格式 (version=2)
 * ```json
 * {
 *   "version": 2,
 *   "globalSettings": {
 *     "deadzone": 0.15,
 *     "lookSensitivity": 0.5,
 *     "cursorSpeed": 1.0,
 *     "lookSmoothing": 0.5,
 *     "lookAcceleration": 1.5
 *   },
 *   "commonLayer": {
 *     "name": "Common",
 *     "buttonMappings": {
 *       "A": { "action": { "type": "keyboard", "keyCode": 62 }, "subCommands": [] },
 *       "B": { "action": { "type": "mouse", "button": "RIGHT" }, "subCommands": [] }
 *     }
 *   },
 *   "layers": [
 *     {
 *       "name": "Layer1",
 *       "triggerButton": "DPAD_UP",
 *       "buttonMappings": {
 *         "A": { "action": { "type": "keyboard", "keyCode": 57 }, "subCommands": [7] }
 *       }
 *     }
 *   ]
 * }
 * ```
 *
 * ## 设计原则
 * 1. 所有枚举使用名称字符串（如 "A"、"DPAD_UP"），人类可读
 * 2. 动作使用 type 字段区分类型（"keyboard"/"mouse"/"switchLayer"/"mouseMove"/"lookAround"）
 * 3. 子命令是 keyCode 整数列表
 * 4. 使用 Android 内置 org.json，无额外依赖
 */
object ControllerConfig {

    const val CONFIG_VERSION = 2

    /**
     * 将 [ControllerProfile] 序列化为 JSON 字符串
     *
     * @param profile 控制器配置
     * @param indent 缩进空格数（0=紧凑模式）
     * @param appConfig 运行时配置（非 null 时写入顶层 `settings` 字段，随配置一起导出）
     * @return JSON 字符串
     */
    fun toJson(profile: ControllerProfile, indent: Int = 2, appConfig: AppConfig? = null): String {
        val json = JSONObject()
        json.put("version", CONFIG_VERSION)
        json.put("globalSettings", globalSettingsToJson(profile.globalSettings))
        json.put("commonLayer", layerToJson(profile.commonLayer))
        val layersArray = JSONArray()
        profile.layers.forEach { layer ->
            layersArray.put(layerToJson(layer))
        }
        json.put("layers", layersArray)
        if (appConfig != null) {
            json.put("settings", appConfigToJson(appConfig))
        }
        return json.toString(indent)
    }

    // ===== AppConfig（运行时配置）序列化 =====

    /**
     * 将 [AppConfig] 序列化为 JSON 对象（写入配置文件顶层 `settings` 字段）
     */
    fun appConfigToJson(cfg: AppConfig): JSONObject {
        val json = JSONObject()
        json.put("serverHost", cfg.serverHost)
        json.put("serverPort", cfg.serverPort)
        json.put("smartPauseEnabled", cfg.smartPauseEnabled)
        val whitelist = JSONArray()
        cfg.captureWhitelist.forEach { whitelist.put(it) }
        json.put("captureWhitelist", whitelist)
        json.put("captureEnabled", cfg.captureEnabled)
        json.put("launcherPackage", cfg.launcherPackage)
        json.put("gameExePath", cfg.gameExePath)
        return json
    }

    /**
     * 从 JSON 对象解析 [AppConfig]（缺失字段使用默认值）
     */
    fun parseAppConfig(json: JSONObject?): AppConfig {
        if (json == null) return AppConfig()
        return AppConfig(
            serverHost = json.optString("serverHost", AppConfig.DEFAULT_HOST),
            serverPort = json.optInt("serverPort", AppConfig.DEFAULT_PORT),
            smartPauseEnabled = json.optBoolean("smartPauseEnabled", true),
            captureWhitelist = json.optJSONArray("captureWhitelist")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
            } ?: AppConfig.DEFAULT_WHITELIST,
            captureEnabled = json.optBoolean("captureEnabled", true),
            launcherPackage = json.optString("launcherPackage", AppConfig.DEFAULT_LAUNCHER),
            gameExePath = json.optString("gameExePath", "")
        )
    }

    /**
     * 从完整配置文件 JSON 字符串解析 AppConfig（顶层 `settings` 字段）
     */
    fun appConfigFromJsonString(jsonString: String): AppConfig {
        val json = JSONObject(jsonString)
        return parseAppConfig(json.optJSONObject("settings"))
    }

    /**
     * 从 JSON 字符串反序列化为 [ControllerProfile]
     *
     * @param jsonString JSON 字符串
     * @return 控制器配置
     * @throws org.json.JSONException JSON 格式错误时抛出
     */
    fun fromJson(jsonString: String): ControllerProfile {
        val json = JSONObject(jsonString)
        val version = json.optInt("version", 1)
        if (version != CONFIG_VERSION) {
            throw IllegalArgumentException("Unsupported config version: $version, expected: $CONFIG_VERSION")
        }

        val globalSettings = json.optJSONObject("globalSettings")?.let { parseGlobalSettings(it) }
            ?: GlobalSettings()

        val commonLayerJson = json.getJSONObject("commonLayer")
        val commonLayer = parseLayer(commonLayerJson, isCommon = true)

        val layersArray = json.optJSONArray("layers") ?: JSONArray()
        val layers = (0 until layersArray.length()).map { i ->
            parseLayer(layersArray.getJSONObject(i), isCommon = false)
        }

        return ControllerProfile(
            commonLayer = commonLayer,
            layers = layers,
            globalSettings = globalSettings
        )
    }

    // ===== 内部序列化方法 =====

    private fun globalSettingsToJson(settings: GlobalSettings): JSONObject {
        val json = JSONObject()
        json.put("deadzone", settings.deadzone)
        json.put("lookSensitivity", settings.lookSensitivity)
        json.put("cursorSpeed", settings.cursorSpeed)
        json.put("lookSmoothing", settings.lookSmoothing)
        json.put("lookAcceleration", settings.lookAcceleration)
        return json
    }

    private fun parseGlobalSettings(json: JSONObject): GlobalSettings {
        return GlobalSettings(
            deadzone = json.optDouble("deadzone", 0.15).toFloat(),
            lookSensitivity = json.optDouble("lookSensitivity", 0.5).toFloat(),
            cursorSpeed = json.optDouble("cursorSpeed", 1.0).toFloat(),
            lookSmoothing = json.optDouble("lookSmoothing", 0.5).toFloat(),
            lookAcceleration = json.optDouble("lookAcceleration", 1.5).toFloat()
        )
    }

    private fun layerToJson(layer: OperationLayer): JSONObject {
        val json = JSONObject()
        json.put("name", layer.name)
        if (layer.triggerButton != null) {
            json.put("triggerButton", layer.triggerButton.name)
        }
        val mappings = JSONObject()
        layer.buttonMappings.forEach { (button, mapping) ->
            mappings.put(button.name, mappingToJson(mapping))
        }
        json.put("buttonMappings", mappings)
        return json
    }

    private fun parseLayer(json: JSONObject, isCommon: Boolean): OperationLayer {
        val name = json.getString("name")
        val triggerButtonStr = json.optString("triggerButton", null)
        val triggerButton = if (triggerButtonStr != null && triggerButtonStr.isNotEmpty()) {
            runCatching { ControllerButton.valueOf(triggerButtonStr) }.getOrNull()
        } else null

        val mappings = mutableMapOf<ControllerButton, KeyMapping>()
        val mappingsJson = json.optJSONObject("buttonMappings") ?: JSONObject()
        mappingsJson.keys().forEach { buttonName ->
            val button = runCatching { ControllerButton.valueOf(buttonName) }.getOrNull()
            if (button != null) {
                val mappingJson = mappingsJson.getJSONObject(buttonName)
                val mapping = parseMapping(mappingJson)
                if (mapping != null) {
                    mappings[button] = mapping
                }
            }
        }

        return OperationLayer(
            name = name,
            triggerButton = if (isCommon) null else triggerButton,
            buttonMappings = mappings
        )
    }

    private fun mappingToJson(mapping: KeyMapping): JSONObject {
        val json = JSONObject()
        json.put("action", actionToJson(mapping.action))
        val subArray = JSONArray()
        mapping.subCommands.forEach { subArray.put(it) }
        json.put("subCommands", subArray)
        return json
    }

    private fun parseMapping(json: JSONObject): KeyMapping? {
        val actionJson = json.getJSONObject("action")
        val action = parseAction(actionJson) ?: return null
        val subArray = json.optJSONArray("subCommands") ?: JSONArray()
        val subCommands = (0 until subArray.length()).map { subArray.getInt(it) }
        if (subCommands.size > KeyMapping.MAX_SUB_COMMANDS) {
            return null  // 子命令超过限制，跳过
        }
        return KeyMapping(action, subCommands)
    }

    private fun actionToJson(action: MappedAction): JSONObject {
        val json = JSONObject()
        when (action) {
            is MappedAction.KeyboardKey -> {
                json.put("type", "keyboard")
                json.put("keyCode", action.keyCode)
            }
            is MappedAction.MouseClick -> {
                json.put("type", "mouse")
                json.put("button", action.button.name)
            }
            is MappedAction.MouseToggle -> {
                json.put("type", "mouseToggle")
                json.put("button", action.button.name)
            }
            is MappedAction.SwitchLayer -> {
                json.put("type", "switchLayer")
                json.put("layerName", action.layerName)
            }
            is MappedAction.MouseMove -> {
                json.put("type", "mouseMove")
            }
            is MappedAction.LookAround -> {
                json.put("type", "lookAround")
            }
            is MappedAction.MouseScrollUp -> {
                json.put("type", "mouseScrollUp")
            }
            is MappedAction.MouseScrollDown -> {
                json.put("type", "mouseScrollDown")
            }
        }
        return json
    }

    private fun parseAction(json: JSONObject): MappedAction? {
        val type = json.getString("type")
        return when (type) {
            "keyboard" -> {
                val keyCode = json.getInt("keyCode")
                MappedAction.KeyboardKey(keyCode)
            }
            "mouse" -> {
                val buttonStr = json.getString("button")
                val button = runCatching { MouseButton.valueOf(buttonStr) }.getOrNull()
                    ?: return null
                MappedAction.MouseClick(button)
            }
            "mouseToggle" -> {
                val buttonStr = json.getString("button")
                val button = runCatching { MouseButton.valueOf(buttonStr) }.getOrNull()
                    ?: return null
                MappedAction.MouseToggle(button)
            }
            "switchLayer" -> {
                val layerName = json.getString("layerName")
                MappedAction.SwitchLayer(layerName)
            }
            "mouseMove" -> MappedAction.MouseMove
            "lookAround" -> MappedAction.LookAround
            "mouseScrollUp" -> MappedAction.MouseScrollUp
            "mouseScrollDown" -> MappedAction.MouseScrollDown
            else -> null
        }
    }
}
