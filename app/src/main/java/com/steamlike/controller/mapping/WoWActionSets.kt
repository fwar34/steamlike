package com.steamlike.controller.mapping

/**
 * WoW 动作集预设
 *
 * 定义操作层的显示名称映射，用于悬浮窗 UI 显示。
 *
 * ## 层名称对照
 * 配置文件中使用 "Layer1"-"Layer10" 作为层名，
 * 悬浮窗按钮显示对应的中文名称（如 "战斗"、"骑乘"等）。
 *
 * ## 默认层分配
 * - Layer1: 战斗 - A/B/X/Y→技能5-8, D-Pad→9/0/-/=
 * - Layer2: 骑乘 - 移动摇杆更灵敏
 * - Layer3: 瞄准 - 右摇杆更精准、移动更慢
 * - Layer4: 拾取 - A→右键拾取, X→左键全拿
 * - Layer5: 潜行 - A→潜行, 移动更小心
 * - Layer6: 钓鱼 - A→钓鱼
 * - Layer7: 对战 - A/B/X/Y→5-8, 摇杆更快
 * - Layer8: 团本 - D-Pad→团队标记 (触发键: Touchpad)
 * - Layer9: 旅行 - A→自动跑, B→坐骑
 * - Layer10: 自定义 - 空层(可运行时配置)
 */
object WoWActionSets {

    /**
     * 操作层名称映射（层名 → 显示名称）
     *
     * 用于悬浮窗 UI 创建层切换按钮。
     * 每个元素是 (内部层名, 中文显示名) 的 Pair。
     */
    val LAYER_NAMES: List<Pair<String, String>> = listOf(
        "Layer1" to "战斗",
        "Layer2" to "骑乘",
        "Layer3" to "瞄准",
        "Layer4" to "拾取",
        "Layer5" to "潜行",
        "Layer6" to "钓鱼",
        "Layer7" to "对战",
        "Layer8" to "团本",
        "Layer9" to "旅行",
        "Layer10" to "自定义"
    )
}
