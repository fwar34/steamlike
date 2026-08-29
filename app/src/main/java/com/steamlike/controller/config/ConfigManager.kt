package com.steamlike.controller.config // 包声明：config 配置模块

import android.content.Context // 导入 Android 上下文类
import android.net.Uri // 导入 URI 类（SAF 文件选择使用）
import android.util.Log // 导入日志工具类
import com.steamlike.controller.core.ControllerProfile // 导入控制器配置数据类
import com.steamlike.controller.core.SteamInput // 导入主控制器类
import java.io.File // 导入文件类（语法：java.io.File=文件读写）

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
class ConfigManager( // 配置管理器类（语法：class=类声明）
    private val context: Context, // 注入的 Android 上下文（语法：private val=私有只读属性）
    private val steamInput: SteamInput // 注入的主控制器实例
) { // 结束构造参数列表，开始类体
    private val TAG = "ConfigManager" // 日志标签常量

    /** 内部存储配置文件名 */
    private val configFileName = CONFIG_FILE_NAME // 引用伴生对象中的文件名常量

    /** 内部存储配置文件 */
    private val configFile: File get() = File(context.filesDir, configFileName) // 配置文件对象（语法：get()=自定义属性 getter，访问时实时构造）

    companion object { // 伴生对象：存放类级常量（语法：companion object=类级静态成员容器）
        /** 内部存储配置文件名（AppConfigStore 共用） */
        const val CONFIG_FILE_NAME = "steamlike_config.json" // 配置文件名常量（语法：const val=编译期常量）
    } // 结束伴生对象

    /**
     * 检查内部存储是否存在配置文件
     */
    fun hasConfigFile(): Boolean = configFile.exists() // 判断配置文件是否存在（语法：fun=函数；表达式体）

    /**
     * 获取配置文件大小（字节）
     */
    fun getConfigFileSize(): Long = if (configFile.exists()) configFile.length() else 0L // 存在则返回大小，否则返回 0（语法：if 表达式）

    /**
     * 从内部存储加载配置
     *
     * 如果文件不存在，使用默认配置并保存。
     *
     * @return 加载的 [ControllerProfile]
     */
    fun loadFromInternal(): ControllerProfile { // 从内部存储加载配置
        return try { // 尝试加载（语法：try/catch=异常捕获）
            if (configFile.exists()) { // 若配置文件存在
                val json = configFile.readText() // 读取文件全部文本（语法：readText()=读取文本）
                val profile = ControllerConfig.fromJson(json) // 反序列化为配置对象
                steamInput.loadProfile(profile) // 应用到运行时
                Log.i(TAG, "Config loaded from internal storage: ${configFile.name}") // 记录加载日志（语法：字符串模板 ${}）
                profile // 返回加载的配置（块内最后一行即返回值）
            } else { // 文件不存在时
                Log.i(TAG, "Config file not found, using default") // 记录提示日志
                val default = ControllerProfile.createDefault() // 创建默认配置
                saveToInternal(default) // 保存默认配置到内部存储
                steamInput.loadProfile(default) // 应用到运行时
                default // 返回默认配置
            } // 结束 if-else 块
        } catch (e: Exception) { // 捕获加载异常（语法：catch=捕获异常，e=异常对象）
            Log.e(TAG, "Failed to load config, using default", e) // 记录错误日志
            val default = ControllerProfile.createDefault() // 创建默认配置兜底
            steamInput.loadProfile(default) // 应用到运行时
            default // 返回默认配置
        } // 结束 catch 块
    } // 结束 loadFromInternal 函数

    /**
     * 保存配置到内部存储
     *
     * 自动带上当前运行时配置（[AppConfigStore.load] 的 settings 字段），
     * 避免覆盖白名单/捕获开关等设置。
     */
    fun saveToInternal(profile: ControllerProfile) { // 保存配置到内部存储
        try { // 尝试保存
            val appConfig = AppConfigStore.load(context) // 读取当前运行时配置
            val json = ControllerConfig.toJson(profile, 2, appConfig) // 序列化为 JSON（缩进 2，带上运行时配置）
            configFile.writeText(json) // 写入文件（语法：writeText()=写入文本）
            Log.i(TAG, "Config saved to internal storage: ${configFile.name} (${configFile.length()} bytes)") // 记录保存日志（语法：字符串模板）
        } catch (e: Exception) { // 捕获保存异常
            Log.e(TAG, "Failed to save config", e) // 记录错误日志
        } // 结束 catch 块
    } // 结束 saveToInternal 函数

    /**
     * 导出配置到指定 URI（通过 SAF）
     *
     * 导出内容包含按键映射 + 运行时配置（settings），可完整备份/迁移。
     *
     * @param uri 用户通过 SAF 选择的文件 URI
     * @return true=成功
     */
    fun saveToUri(uri: Uri): Boolean { // 导出配置到指定 URI（语法：fun=函数声明）
        return try { // 尝试导出
            val appConfig = AppConfigStore.load(context) // 读取当前运行时配置
            val json = ControllerConfig.toJson(steamInput.profile, 2, appConfig) // 序列化当前运行时配置
            context.contentResolver.openOutputStream(uri)?.use { stream -> // 打开输出流（语法：?.=安全调用；use=自动关闭资源）
                stream.write(json.toByteArray()) // 写入 JSON 字节（语法：toByteArray()=字符串转字节数组）
            } // 结束 use 块
            Log.i(TAG, "Config exported to URI") // 记录导出日志
            true // 返回成功
        } catch (e: Exception) { // 捕获导出异常
            Log.e(TAG, "Failed to export config", e) // 记录错误日志
            false // 返回失败
        } // 结束 catch 块
    } // 结束 saveToUri 函数

    /**
     * 从指定 URI 导入配置（通过 SAF）
     *
     * @param uri 用户通过 SAF 选择的文件 URI
     * @return true=成功
     */
    fun loadFromUri(uri: Uri): Boolean { // 从指定 URI 导入配置
        return try { // 尝试导入
            val json = context.contentResolver.openInputStream(uri)?.use { stream -> // 打开输入流（语法：?.=安全调用；use=自动关闭）
                stream.bufferedReader().readText() // 用缓冲读取器读取全部文本
            } ?: return false // 打开失败则返回 false（语法：?:=空值合并）
            val profile = ControllerConfig.fromJson(json) // 反序列化为配置对象
            steamInput.loadProfile(profile) // 应用到运行时
            saveToInternal(profile) // 保存到内部存储
            Log.i(TAG, "Config imported from URI") // 记录导入日志
            true // 返回成功
        } catch (e: Exception) { // 捕获导入异常
            Log.e(TAG, "Failed to import config", e) // 记录错误日志
            false // 返回失败
        } // 结束 catch 块
    } // 结束 loadFromUri 函数

    /**
     * 重置为默认配置
     */
    fun resetToDefault() { // 重置为默认配置
        val default = ControllerProfile.createDefault() // 创建默认配置
        steamInput.loadProfile(default) // 应用到运行时
        saveToInternal(default) // 保存到内部存储
        Log.i(TAG, "Config reset to default") // 记录重置日志
    } // 结束 resetToDefault 函数
} // 结束 ConfigManager 类
