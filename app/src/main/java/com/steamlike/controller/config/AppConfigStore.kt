package com.steamlike.controller.config // 包声明：config 配置模块

import android.content.Context // 导入 Android 上下文类
import android.util.Log // 导入日志工具类
import org.json.JSONObject // 导入 Android 内置 JSON 对象类
import java.io.File // 导入文件类（语法：java.io.File=文件读写）

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
object AppConfigStore { // 单例对象：AppConfig 的持久化存储（语法：object=单例声明）
    private const val TAG = "AppConfigStore" // 日志标签常量

    /** 内部配置文件路径（与 ConfigManager 一致） */
    private fun configFile(context: Context): File = // 获取内部配置文件（语法：表达式体函数）
        File(context.filesDir, ConfigManager.CONFIG_FILE_NAME) // 构造文件对象（语法：File=构造文件）

    /**
     * 从配置文件读取 AppConfig
     *
     * @param context Context
     * @return AppConfig；文件不存在或解析失败时返回默认值
     */
    fun load(context: Context): AppConfig { // 读取 AppConfig
        return try { // 尝试读取（语法：try/catch=异常捕获）
            val file = configFile(context) // 获取配置文件
            if (file.exists()) { // 若文件存在
                ControllerConfig.appConfigFromJsonString(file.readText()) // 读取并解析 settings 字段（语法：readText()=读取文本）
            } else { // 文件不存在
                AppConfig() // 返回默认配置
            } // 结束 if-else 块
        } catch (e: Exception) { // 捕获读取异常
            Log.e(TAG, "Failed to load AppConfig, using default", e) // 记录错误日志
            AppConfig() // 返回默认配置
        } // 结束 catch 块
    } // 结束 load 函数

    /**
     * 更新配置文件中的 settings（保留 profile 部分）
     *
     * @param context Context
     * @param appConfig 新的运行时配置
     */
    fun save(context: Context, appConfig: AppConfig) { // 保存 AppConfig 到配置文件的 settings 字段
        try { // 尝试保存
            val file = configFile(context) // 获取配置文件
            val root = if (file.exists()) { // 文件已存在则读取根对象
                JSONObject(file.readText()) // 解析已有 JSON（保留 profile 部分）
            } else { // 文件不存在
                JSONObject().put("version", ControllerConfig.CONFIG_VERSION) // 新建根对象并写入版本号
            } // 结束 if-else 块
            root.put("settings", ControllerConfig.appConfigToJson(appConfig)) // 覆盖写入 settings 字段
            file.writeText(root.toString(2)) // 写回文件，缩进 2（语法：writeText()=写入文本）
            Log.i(TAG, "AppConfig saved: ${file.name} (${file.length()} bytes)") // 记录保存日志（语法：字符串模板）
        } catch (e: Exception) { // 捕获保存异常
            Log.e(TAG, "Failed to save AppConfig", e) // 记录错误日志
        } // 结束 catch 块
    } // 结束 save 函数
} // 结束 AppConfigStore 单例对象
