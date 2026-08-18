package com.steamlike.controller.config

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * AppConfig 持久化存储
 *
 * 将运行时配置读写到 `steamlike_config.json` 的 `settings` 字段：
 * - [load]: 读取 AppConfig（settings 缺失或文件不存在时返回默认值）
 * - [save]: 更新文件中的 settings（保留 profile 部分，按键映射不受影响）
 *
 * 与 [ConfigManager] 的分工：
 * - [ConfigManager] 负责 ControllerProfile（层/映射/全局设置）的读写
 * - [AppConfigStore] 负责运行时配置（服务器/智能暂停/白名单/捕获开关/拉起应用）
 * - 两者写同一个文件，互不覆盖（save 只改 settings，ConfigManager 保存时带上 settings）
 */
object AppConfigStore {
    private const val TAG = "AppConfigStore"

    /** 内部配置文件路径（与 ConfigManager 一致） */
    private fun configFile(context: Context): File =
        File(context.filesDir, ConfigManager.CONFIG_FILE_NAME)

    /**
     * 从配置文件读取 AppConfig
     *
     * @param context Context
     * @return AppConfig；文件不存在或解析失败时返回默认值
     */
    fun load(context: Context): AppConfig {
        return try {
            val file = configFile(context)
            if (file.exists()) {
                ControllerConfig.appConfigFromJsonString(file.readText())
            } else {
                AppConfig()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load AppConfig, using default", e)
            AppConfig()
        }
    }

    /**
     * 更新配置文件中的 settings（保留 profile 部分）
     *
     * @param context Context
     * @param appConfig 新的运行时配置
     */
    fun save(context: Context, appConfig: AppConfig) {
        try {
            val file = configFile(context)
            val root = if (file.exists()) {
                JSONObject(file.readText())
            } else {
                JSONObject().put("version", ControllerConfig.CONFIG_VERSION)
            }
            root.put("settings", ControllerConfig.appConfigToJson(appConfig))
            file.writeText(root.toString(2))
            Log.i(TAG, "AppConfig saved: ${file.name} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save AppConfig", e)
        }
    }
}
