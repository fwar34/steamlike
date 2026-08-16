package com.steamlike.controller.mapping

import com.steamlike.controller.core.*

/**
 * WoW乌龟服1.18.1手柄映射预设（无操作集版本）
 *
 * 架构:
 * - 公共层(commonLayer): 始终激活的基础按键映射, 定义所有动作和默认绑定
 * - 10个操作层: 每个层默认继承公共层的全部绑定, 可单独覆盖任意按键映射
 * - 组合键(ChordBinding): 参考 Steam 子指令，同一按钮在不同修饰键下触发不同动作
 *
 * 组合键预设（公共层）:
 *   A + RB → 选怪        B + RB → 面向目标
 *   X + RB → 回复密语    Y + RB → 打开地图
 *   D-Pad + L3 → 快捷栏5-8
 *   D-Pad + R3 → 快捷栏9/0/-/=
 *   （D-Pad单独 = 快捷栏1-4，共12个快捷栏按键全覆盖）
 *
 * 10个默认操作层:
 *  1. Combat   战斗模式  - A/B/X/Y→技能5-8, D-Pad→9/0/-/=
 *  2. Mount    骑乘模式  - 移动摇杆更灵敏
 *  3. Aim      瞄准模式  - 右摇杆更精准、移动更慢
 *  4. Loot     拾取模式  - A→右键拾取, X→左键全拿
 *  5. Stealth  潜行模式  - A→潜行技能
 *  6. Fishing  钓鱼模式  - A→钓鱼宏
 *  7. PvP      对战模式  - A/B/X/Y→技能5-8 + D-Pad→PVP道具
 *  8. Raid     团本模式  - D-Pad→团队标记
 *  9. Travel   旅行模式  - A→自动跑, B→坐骑
 * 10. Custom   自定义    - 空层(用户自行配置)
 */
object WoWActionSets {

    /** 10个操作层的名称定义 */
    val LAYER_NAMES = listOf(
        "Combat" to "战斗",
        "Mount" to "骑乘",
        "Aim" to "瞄准",
        "Loot" to "拾取",
        "Stealth" to "潜行",
        "Fishing" to "钓鱼",
        "PvP" to "对战",
        "Raid" to "团本",
        "Travel" to "旅行",
        "Custom" to "自定义"
    )

    fun setup(steamInput: SteamInput): WoWConfig {
        createCommonLayer(steamInput)
        createCombatLayer(steamInput)
        createMountLayer(steamInput)
        createAimLayer(steamInput)
        createLootLayer(steamInput)
        createStealthLayer(steamInput)
        createFishingLayer(steamInput)
        createPvPLayer(steamInput)
        createRaidLayer(steamInput)
        createTravelLayer(steamInput)
        createCustomLayer(steamInput)

        return WoWConfig(
            commonLayer = steamInput.commonLayer,
            layers = LAYER_NAMES.associate { (name, _) ->
                name to steamInput.actionSetLayers[name]!!
            }
        )
    }

    // ===== 公共层: 基础按键映射（所有操作层默认继承） =====

    private fun createCommonLayer(steamInput: SteamInput) {
        val c = steamInput.commonLayer

        // 所有可能的按钮动作（公共层定义全部, 操作层只覆盖绑定）
        c.addButtonAction("Jump") {}
        c.addButtonAction("Interact") {}
        c.addButtonAction("Attack") {}
        c.addButtonAction("Inventory") {}
        c.addButtonAction("TargetEnemy") {}
        c.addButtonAction("FaceTarget") {}
        c.addButtonAction("Menu") {}
        c.addButtonAction("Chat") {}
        c.addButtonAction("AutoRun") {}
        c.addButtonAction("Reply") {}
        c.addButtonAction("Map") {}
        c.addButtonAction("Slot1") {}
        c.addButtonAction("Slot2") {}
        c.addButtonAction("Slot3") {}
        c.addButtonAction("Slot4") {}
        c.addButtonAction("Slot5") {}
        c.addButtonAction("Slot6") {}
        c.addButtonAction("Slot7") {}
        c.addButtonAction("Slot8") {}
        c.addButtonAction("Slot9") {}
        c.addButtonAction("Slot0") {}
        c.addButtonAction("SlotDash") {}
        c.addButtonAction("SlotEqual") {}
        c.addButtonAction("Loot") {}
        c.addButtonAction("CloseLoot") {}
        c.addButtonAction("TakeAll") {}
        c.addButtonAction("Stealth") {}
        c.addButtonAction("Fish") {}
        c.addButtonAction("MountUp") {}
        c.addButtonAction("Mark1") {}
        c.addButtonAction("Mark2") {}
        c.addButtonAction("Mark3") {}
        c.addButtonAction("Mark4") {}

        // 摇杆
        c.addStickAction("Move") {
            deadzone = 0.2f
            responseCurve = 1.3f
        }
        c.addStickAction("Look") {
            deadzone = 0.15f
            responseCurve = 1.5f
        }
        c.addStickAction("Cursor") {
            deadzone = 0.25f
            responseCurve = 1.0f
        }

        // 扳机
        c.addTriggerAction("Modifier") { pressThreshold = 0.3f }
        c.addTriggerAction("Cast") { pressThreshold = 0.3f }

        // 默认绑定
        with(steamInput) {
            bindButton(ControllerButton.A, "Jump")
            bindButton(ControllerButton.B, "Interact")
            bindButton(ControllerButton.X, "Attack")
            bindButton(ControllerButton.Y, "Inventory")
            bindButton(ControllerButton.LEFT_SHOULDER, "TargetEnemy")
            bindButton(ControllerButton.RIGHT_SHOULDER, "FaceTarget")
            bindButton(ControllerButton.MENU, "Menu")
            bindButton(ControllerButton.OPTIONS, "Chat")
            bindButton(ControllerButton.LEFT_STICK_CLICK, "AutoRun")
            bindButton(ControllerButton.RIGHT_STICK_CLICK, "Reply")
            bindButton(ControllerButton.GUIDE, "Map")
            bindButton(ControllerButton.DPAD_UP, "Slot1")
            bindButton(ControllerButton.DPAD_DOWN, "Slot2")
            bindButton(ControllerButton.DPAD_LEFT, "Slot3")
            bindButton(ControllerButton.DPAD_RIGHT, "Slot4")

            bindStick(ControllerStick.LEFT_STICK, "Move")
            bindStick(ControllerStick.RIGHT_STICK, "Look")
            bindTrigger(ControllerTrigger.LEFT_TRIGGER, "Modifier")
            bindTrigger(ControllerTrigger.RIGHT_TRIGGER, "Cast")
        }

        // ===== 组合键绑定 (Chord Bindings) =====
        // 参考 Steam Input 的子指令机制，同一按钮在不同修饰键下触发不同动作。
        //
        // 修饰键说明:
        //   RB = 右肩键（默认=FaceTarget，组合时作为功能修饰键）
        //   L3 = 左摇杆按下（默认=AutoRun，组合时作为快捷栏扩展修饰键）
        //   R3 = 右摇杆按下（默认=Reply，组合时作为快捷栏扩展修饰键）
        //
        // 注意: 修饰键自身的动作仅在单独按下时触发，
        //       当作为组合键修饰键时不会阻止其自身动作（按下L3仍会触发AutoRun）。

        // --- RB 作为修饰键: A/B/X/Y + RB → 常用功能 ---
        c.addChordBinding(ControllerButton.A, "TargetEnemy",
            setOf(ControllerButton.RIGHT_SHOULDER))      // A + RB → 选怪
        c.addChordBinding(ControllerButton.B, "FaceTarget",
            setOf(ControllerButton.RIGHT_SHOULDER))      // B + RB → 面向目标
        c.addChordBinding(ControllerButton.X, "Reply",
            setOf(ControllerButton.RIGHT_SHOULDER))      // X + RB → 回复密语
        c.addChordBinding(ControllerButton.Y, "Map",
            setOf(ControllerButton.RIGHT_SHOULDER))      // Y + RB → 打开地图

        // --- L3 作为修饰键: D-Pad + L3 → 快捷栏5-8 ---
        c.addChordBinding(ControllerButton.DPAD_UP, "Slot5",
            setOf(ControllerButton.LEFT_STICK_CLICK))    // D-Pad↑ + L3 → 快捷栏5
        c.addChordBinding(ControllerButton.DPAD_DOWN, "Slot6",
            setOf(ControllerButton.LEFT_STICK_CLICK))    // D-Pad↓ + L3 → 快捷栏6
        c.addChordBinding(ControllerButton.DPAD_LEFT, "Slot7",
            setOf(ControllerButton.LEFT_STICK_CLICK))    // D-Pad← + L3 → 快捷栏7
        c.addChordBinding(ControllerButton.DPAD_RIGHT, "Slot8",
            setOf(ControllerButton.LEFT_STICK_CLICK))    // D-Pad→ + L3 → 快捷栏8

        // --- R3 作为修饰键: D-Pad + R3 → 快捷栏9/0/-/= ---
        c.addChordBinding(ControllerButton.DPAD_UP, "Slot9",
            setOf(ControllerButton.RIGHT_STICK_CLICK))   // D-Pad↑ + R3 → 快捷栏9
        c.addChordBinding(ControllerButton.DPAD_DOWN, "Slot0",
            setOf(ControllerButton.RIGHT_STICK_CLICK))   // D-Pad↓ + R3 → 快捷栏0
        c.addChordBinding(ControllerButton.DPAD_LEFT, "SlotDash",
            setOf(ControllerButton.RIGHT_STICK_CLICK))   // D-Pad← + R3 → 快捷栏-
        c.addChordBinding(ControllerButton.DPAD_RIGHT, "SlotEqual",
            setOf(ControllerButton.RIGHT_STICK_CLICK))   // D-Pad→ + R3 → 快捷栏=
    }

    // ===== 操作层1: Combat 战斗模式 =====

    private fun createCombatLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Combat", "战斗模式") {
            // A/B/X/Y → 技能5-8
            overrideButtonBinding(ControllerButton.A, "Slot5")
            overrideButtonBinding(ControllerButton.B, "Slot6")
            overrideButtonBinding(ControllerButton.X, "Slot7")
            overrideButtonBinding(ControllerButton.Y, "Slot8")
            // D-Pad → 技能9/0/-/=
            overrideButtonBinding(ControllerButton.DPAD_UP, "Slot9")
            overrideButtonBinding(ControllerButton.DPAD_DOWN, "Slot0")
            overrideButtonBinding(ControllerButton.DPAD_LEFT, "SlotDash")
            overrideButtonBinding(ControllerButton.DPAD_RIGHT, "SlotEqual")
            // 右摇杆: 更快响应
            overrideStick("Look") {
                deadzone = 0.2f
                responseCurve = 0.8f
            }
        }
    }

    // ===== 操作层2: Mount 骑乘模式 =====

    private fun createMountLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Mount", "骑乘模式") {
            // 移动摇杆更灵敏
            overrideStick("Move") {
                deadzone = 0.15f
                responseCurve = 1.0f
            }
        }
    }

    // ===== 操作层3: Aim 瞄准模式 =====

    private fun createAimLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Aim", "瞄准模式") {
            // 右摇杆: 更大死区 + 更平滑曲线 = 精确瞄准
            overrideStick("Look") {
                deadzone = 0.25f
                responseCurve = 0.5f
            }
            // 左摇杆: 更大死区 + 更平滑曲线 = 精确移动
            overrideStick("Move") {
                deadzone = 0.3f
                responseCurve = 0.8f
            }
        }
    }

    // ===== 操作层4: Loot 拾取模式 =====

    private fun createLootLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Loot", "拾取模式") {
            overrideButtonBinding(ControllerButton.A, "Loot")
            overrideButtonBinding(ControllerButton.B, "CloseLoot")
            overrideButtonBinding(ControllerButton.X, "TakeAll")
            overrideButtonBinding(ControllerButton.Y, "CloseLoot")
        }
    }

    // ===== 操作层5: Stealth 潜行模式 =====

    private fun createStealthLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Stealth", "潜行模式") {
            overrideButtonBinding(ControllerButton.A, "Stealth")
            // 左摇杆: 更大死区 = 潜行时更小心移动
            overrideStick("Move") {
                deadzone = 0.3f
                responseCurve = 0.7f
            }
        }
    }

    // ===== 操作层6: Fishing 钓鱼模式 =====

    private fun createFishingLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Fishing", "钓鱼模式") {
            overrideButtonBinding(ControllerButton.A, "Fish")
            overrideButtonBinding(ControllerButton.B, "CloseLoot")
        }
    }

    // ===== 操作层7: PvP 对战模式 =====

    private fun createPvPLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("PvP", "对战模式") {
            // A/B/X/Y → 技能5-8
            overrideButtonBinding(ControllerButton.A, "Slot5")
            overrideButtonBinding(ControllerButton.B, "Slot6")
            overrideButtonBinding(ControllerButton.X, "Slot7")
            overrideButtonBinding(ControllerButton.Y, "Slot8")
            // D-Pad → 技能9/0/-/= (同Combat, 但摇杆不同)
            overrideButtonBinding(ControllerButton.DPAD_UP, "Slot9")
            overrideButtonBinding(ControllerButton.DPAD_DOWN, "Slot0")
            overrideButtonBinding(ControllerButton.DPAD_LEFT, "SlotDash")
            overrideButtonBinding(ControllerButton.DPAD_RIGHT, "SlotEqual")
            // 更快的摇杆响应
            overrideStick("Look") {
                deadzone = 0.15f
                responseCurve = 1.2f
            }
        }
    }

    // ===== 操作层8: Raid 团本模式 =====

    private fun createRaidLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Raid", "团本模式") {
            // D-Pad → 团队标记
            overrideButtonBinding(ControllerButton.DPAD_UP, "Mark1")
            overrideButtonBinding(ControllerButton.DPAD_DOWN, "Mark2")
            overrideButtonBinding(ControllerButton.DPAD_LEFT, "Mark3")
            overrideButtonBinding(ControllerButton.DPAD_RIGHT, "Mark4")
        }
    }

    // ===== 操作层9: Travel 旅行模式 =====

    private fun createTravelLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Travel", "旅行模式") {
            overrideButtonBinding(ControllerButton.A, "AutoRun")
            overrideButtonBinding(ControllerButton.B, "MountUp")
            // 移动摇杆更灵敏
            overrideStick("Move") {
                deadzone = 0.15f
                responseCurve = 1.0f
            }
        }
    }

    // ===== 操作层10: Custom 自定义（空层, 用户运行时配置） =====

    private fun createCustomLayer(steamInput: SteamInput) {
        steamInput.createActionSetLayer("Custom", "自定义") {
            // 空层: 完全继承公共层绑定
            // 用户可通过 setLayerButtonBinding() 运行时自定义
        }
    }
}

/**
 * WoW 游戏配置数据类
 *
 * 封装 WoW 预设的完整配置: 公共层 + 10 个操作层。
 * 由 [WoWActionSets.setup] 创建，存储在 [KeyboardMouseMapper.wowConfig] 中。
 *
 * ## 字段说明
 * - [commonLayer]: 公共层，始终激活，定义所有动作和默认绑定
 * - [layers]: 操作层映射表，按名称索引（如 "Combat" → ActionSetLayer）
 *
 * ## 使用场景
 * [KeyboardMouseMapper] 通过此配置访问操作层，用于:
 * - 切换层激活状态（[toggleLayer]）
 * - 查询当前激活层（[getActiveLayers]）
 * - 获取所有层用于 UI 显示
 *
 * @param commonLayer 公共层 ActionSet 实例
 * @param layers 操作层映射表（名称 → ActionSetLayer）
 */
data class WoWConfig(
    val commonLayer: ActionSet,
    val layers: Map<String, ActionSetLayer>
) {
    /**
     * 按名称获取操作层
     *
     * @param name 层名称（如 "Combat"）
     * @return 对应的操作层；不存在返回 null
     */
    fun layer(name: String): ActionSetLayer? = layers[name]

    /**
     * 获取所有操作层（按 LAYER_NAMES 定义的顺序）
     *
     * 用于 UI 显示所有可用层（如悬浮窗的 2x5 按钮网格）。
     *
     * @return 操作层列表（顺序与 LAYER_NAMES 一致）
     */
    fun allLayers(): List<ActionSetLayer> =
        WoWActionSets.LAYER_NAMES.mapNotNull { (name, _) -> layers[name] }
}
