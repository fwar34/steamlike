package com.steamlike.controller.config

import android.content.Context
import android.net.Uri
import android.util.Log
import com.steamlike.controller.core.ControllerProfile
import com.steamlike.controller.core.SteamInput
import java.io.File

/**
 * 配置管理器
 *
 * 负责 [ControllerProfile] 配置文件的读写和 [SteamInput] 状态的导入/导出。
 *
 * ## 导出流程
 * ```
 * SteamInput.profile (运行时)
 *      ↓ [ControllerConfig.toJson] 序列化
 * JSON 字符串
 *      ↓ [saveToFile] 内部存储 / [saveToUri] 用户选择位置
 * 文件
 * ```
 *
 * ## 导入流程
 * ```
 * 文件
 *      ↓ [loadFromUri] / [loadFromFile] 读取
 * JSON 字符串
 *      ↓ [ControllerConfig.fromJson] 解析
 * ControllerProfile
 *      ↓ [SteamInput.loadProfile] 应用到运行时
 * ```
 *
 * ## 文件位置
 * - 内部存储: `/data/data/com.steamlike.controller/files/steamlike_config.json`
 * - 导出位置: 用户通过 SAF 选择
 *
 * @param context Android Context
 * @param steamInput Steam 输入控制器
 */
class ConfigManager(
    private val context: Context,
    private val steamInput: SteamInput
) {
    private val TAG = "ConfigManager"

    /** 内部存储配置文件名 */
    private val configFileName = CONFIG_FILE_NAME

    /** 内部存储配置文件 */
    private val configFile: File get() = File(context.filesDir, configFileName)

    companion object {
        /** 内部存储配置文件名（AppConfigStore 共用） */
        const val CONFIG_FILE_NAME = "steamlike_config.json"
    }

    /**
     * 检查内部存储是否存在配置文件
     */
    fun hasConfigFile(): Boolean = configFile.exists()

    /**
     * 获取配置文件大小（字节）
     */
    fun getConfigFileSize(): Long = if (configFile.exists()) configFile.length() else 0L

    /**
     * 从内部存储加载配置
     *
     * 如果文件不存在，使用默认配置并保存。
     *
     * @return 加载的 [ControllerProfile]
     */
    fun loadFromInternal(): ControllerProfile {
        return try {
            if (configFile.exists()) {
                val json = configFile.readText()
                val profile = ControllerConfig.fromJson(json)
                steamInput.loadProfile(profile)
                Log.i(TAG, "Config loaded from internal storage: ${configFile.name}")
                profile
            } else {
                Log.i(TAG, "Config file not found, using default")
                val default = ControllerProfile.createDefault()
                saveToInternal(default)
                steamInput.loadProfile(default)
                default
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config, using default", e)
            val default = ControllerProfile.createDefault()
            steamInput.loadProfile(default)
            default
        }
    }

    /**
     * 保存配置到内部存储
     *
     * 自动带上当前运行时配置（[AppConfigStore.load] 的 settings 字段），
     * 避免覆盖白名单/捕获开关等设置。
     */
    fun saveToInternal(profile: ControllerProfile) {
        try {
            val appConfig = AppConfigStore.load(context)
            val json = ControllerConfig.toJson(profile, 2, appConfig)
            configFile.writeText(json)
            Log.i(TAG, "Config saved to internal storage: ${configFile.name} (${configFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }

    /**
     * 导出配置到指定 URI（通过 SAF）
     *
     * 导出内容包含按键映射 + 运行时配置（settings），可完整备份/迁移。
     *
     * @param uri 用户通过 SAF 选择的文件 URI
     * @return true=成功
     */
    fun saveToUri(uri: Uri): Boolean {
        return try {
            val appConfig = AppConfigStore.load(context)
            val json = ControllerConfig.toJson(steamInput.profile, 2, appConfig)
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray())
            }
            Log.i(TAG, "Config exported to URI")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export config", e)
            false
        }
    }

    /**
     * 从指定 URI 导入配置（通过 SAF）
     *
     * @param uri 用户通过 SAF 选择的文件 URI
     * @return true=成功
     */
    fun loadFromUri(uri: Uri): Boolean {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return false
            val profile = ControllerConfig.fromJson(json)
            steamInput.loadProfile(profile)
            saveToInternal(profile)
            Log.i(TAG, "Config imported from URI")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import config", e)
            false
        }
    }

    /**
     * 重置为默认配置
     */
    fun resetToDefault() {
        val default = ControllerProfile.createDefault()
        steamInput.loadProfile(default)
        saveToInternal(default)
        Log.i(TAG, "Config reset to default")
    }
}
