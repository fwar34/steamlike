package com.steamlike.controller.config

/**
 * 运行时配置（随配置文件持久化）
 *
 * 集中存储除按键映射外的所有运行参数，随 `steamlike_config.json`
 * 的 `settings` 字段一并导入/导出，避免散落在多处 SharedPreferences。
 *
 * ## 包含的配置
 * - [serverHost]/[serverPort]: TCP 监听地址/端口
 * - [smartPauseEnabled]: 智能暂停开关（前台应用检测自动移除/恢复焦点窗口）
 * - [captureWhitelist]: 捕获白名单（前台在此列表内时保持手柄捕获，支持多个包名）
 * - [captureEnabled]: 捕获总开关（悬浮窗按钮与 App 内开关双向同步）
 * - [launcherPackage]: 悬浮窗"游戏"按钮拉起的应用包名
 * - [gameExePath]: 游戏 EXE 完整路径（Winlator 内，如 C:\\WoW\\Wow.exe），
 *   随 control.bat 导出：脚本先启动 inputbridge_client.exe，成功后再启动游戏
 *
 * @param serverHost TCP 监听地址
 * @param serverPort TCP 监听端口
 * @param smartPauseEnabled 智能暂停开关
 * @param captureWhitelist 捕获白名单包名列表（多个）
 * @param captureEnabled 捕获总开关
 * @param launcherPackage 悬浮窗拉起的目标应用包名
 * @param gameExePath 游戏 EXE 路径（Winlator 内），空表示不自动启动游戏
 */
data class AppConfig(
    val serverHost: String = DEFAULT_HOST,
    val serverPort: Int = DEFAULT_PORT,
    val smartPauseEnabled: Boolean = true,
    val captureWhitelist: List<String> = DEFAULT_WHITELIST,
    val captureEnabled: Boolean = true,
    val launcherPackage: String = DEFAULT_LAUNCHER,
    val gameExePath: String = ""
) {
    companion object {
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 27015
        const val DEFAULT_LAUNCHER = "com.winlator"

        /**
         * 默认捕获白名单（前台应用在此集合内时保持焦点窗口捕获手柄）
         *
         * 包含 Winlator 官方包名及其常见分支（如 com.winlator.hub）。
         * 用户可在 App 内增删，支持多个包名。
         */
        val DEFAULT_WHITELIST = listOf("com.winlator", "com.winlator.hub")

        /**
         * 解析白名单字符串为包名列表
         *
         * 支持分隔符：逗号（中英文）、分号、空格、换行。
         * 空输入返回默认白名单。
         *
         * @param raw 原始字符串（如 "com.winlator, com.winlator.hub"）
         * @return 去重后的包名列表
         */
        fun parseWhitelist(raw: String): List<String> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DEFAULT_WHITELIST
            return trimmed.split(',', '，', ';', '；', ' ', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
    }
}
