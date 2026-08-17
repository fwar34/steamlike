package com.steamlike.controller.core

import com.steamlike.controller.injection.MouseButton

/**
 * 鼠标按钮显示名称扩展函数
 */
fun MouseButton.toDisplayName(): String = when (this) {
    MouseButton.LEFT -> "鼠标左键"
    MouseButton.RIGHT -> "鼠标右键"
    MouseButton.MIDDLE -> "鼠标中键"
    MouseButton.FORWARD -> "鼠标前进键"
    MouseButton.BACK -> "鼠标后退键"
}

/**
 * 映射动作类型（sealed class，密封类）
 *
 * 每个手柄按键可以映射到以下动作之一。使用密封类确保 when 表达式穷举所有可能，
 * 编译器会在添加新动作类型时提示更新所有处理逻辑。
 *
 * ## 支持的动作类型
 * - [KeyboardKey]: 键盘按键（包括字母/数字/功能键/Ctrl/Shift/Alt 等）
 * - [MouseClick]: 鼠标点击（左/右/中键）
 * - [SwitchLayer]: 切换操作层（按住激活，松开回公共层）
 * - [MouseMove]: 鼠标移动（摇杆专用，控制光标）
 * - [LookAround]: 视角控制（摇杆专用，控制游戏视角）
 *
 * ## Android 知识点: sealed class
 * Kotlin 密封类限制子类必须在同一文件中定义，编译器能保证 when 表达式覆盖所有分支。
 * 适用于有限状态的场景（如动作类型枚举）。与 enum class 不同，密封类每个子类可以
 * 有不同的字段，更灵活。
 */
sealed class MappedAction {

    /**
     * 键盘按键动作
     *
     * 将手柄按键映射为键盘按键。keyCode 使用 Android [KeyEvent] 常量，
     * 由 [com.steamlike.controller.injection.BridgeInputInjector] 转换为 Windows VK Code 注入。
     *
     * ## 支持的按键
     * - 字母: A-Z ([KeyEvent.KEYCODE_A] ~ [KeyEvent.KEYCODE_Z])
     * - 数字: 0-9 ([KeyEvent.KEYCODE_0] ~ [KeyEvent.KEYCODE_9])
     * - 功能键: F1-F12 ([KeyEvent.KEYCODE_F1] ~ [KeyEvent.KEYCODE_F12])
     * - 修饰键: Ctrl/Shift/Alt ([KeyEvent.KEYCODE_CTRL_LEFT] 等)
     * - 符号: 空格/回车/Tab/Esc/方向键等
     *
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量
     */
    data class KeyboardKey(val keyCode: Int) : MappedAction()

    /**
     * 鼠标点击动作
     *
     * 将手柄按键映射为鼠标按钮点击。
     *
     * @param button 鼠标按钮（[MouseButton.LEFT]/[MouseButton.RIGHT]/[MouseButton.MIDDLE]）
     */
    data class MouseClick(val button: MouseButton) : MappedAction()

    /**
     * 切换操作层动作
     *
     * 将手柄按键映射为操作层切换。按下时激活目标层，松开时回到公共层。
     * 用于实现"按住D-Pad上切换到Layer1，松开回Common"等功能。
     *
     * @param layerName 目标层名称（如 "Layer1"、"Combat"）
     */
    data class SwitchLayer(val layerName: String) : MappedAction()

    /**
     * 鼠标移动动作（摇杆专用）
     *
     * 将摇杆映射为鼠标相对移动，用于控制光标位置。
     * 摇杆X/Y值会乘以 [GlobalSettings.cursorSpeed] 后发送给 Windows。
     */
    data object MouseMove : MappedAction()

    /**
     * 视角控制动作（摇杆专用）
     *
     * 将摇杆映射为鼠标相对移动，用于控制游戏视角（如 WoW 中的右键拖拽视角）。
     * 摇杆X/Y值会乘以 [GlobalSettings.lookSensitivity] 后发送给 Windows。
     */
    data object LookAround : MappedAction()

    /**
     * 鼠标长按切换动作
     *
     * 第一次按下发送 MouseDown（不松开），第二次按下发送 MouseUp。
     * 适用于"按一下开始长按，再按一下释放"的场景。
     *
     * @param button 鼠标按钮（[MouseButton.LEFT]/[MouseButton.RIGHT]等）
     */
    data class MouseToggle(val button: MouseButton) : MappedAction()
}

/**
 * 按键映射定义
 *
 * 每个手柄按键的完整映射，包含主动作和最多 3 个子命令。
 *
 * ## 子命令（Sub-Command）机制
 * 子命令是 Steam Input 风格的组合键实现。每个按键映射可以添加最多 3 个子命令，
 * 最终输出为组合键。
 *
 * ### 工作原理
 * ```
 * 手柄X → KeyMapping(action=KeyboardKey(Alt), subCommands=[KEYCODE_3])
 * 输出: 按下Alt → 按下3 → 松开3 → 松开Alt（即 Alt+3 组合键）
 * ```
 *
 * ### 多子命令示例
 * ```
 * 手柄X → KeyMapping(action=KeyboardKey(Ctrl), subCommands=[KEYCODE_SHIFT, KEYCODE_3])
 * 输出: 按下Ctrl → 按下Shift → 按下3 → 松开3 → 松开Shift → 松开Ctrl（即 Ctrl+Shift+3）
 * ```
 *
 * ### 约束
 * - 子命令只支持键盘按键（[MappedAction.KeyboardKey]），不支持鼠标/切换层
 * - 子命令最多 3 个
 * - 主动作为 [MappedAction.SwitchLayer] 或摇杆动作时，子命令无效
 *
 * @param action 主动作
 * @param subCommands 子命令列表（最多 3 个 Android [KeyEvent.KEYCODE_*] 常量）
 */
data class KeyMapping(
    val action: MappedAction,
    val subCommands: List<Int> = emptyList()
) {
    init {
        require(subCommands.size <= MAX_SUB_COMMANDS) {
            "子命令最多 $MAX_SUB_COMMANDS 个，当前 ${subCommands.size} 个"
        }
    }

    /**
     * 转换为人类可读的描述字符串（用于 UI 显示）
     *
     * 示例: "Alt+3"、"Ctrl+Shift+3"、"鼠标左键"、"切换到 Layer1"
     */
    fun describe(): String {
        val parts = mutableListOf<String>()
        when (action) {
            is MappedAction.KeyboardKey -> parts.add(keyCodeToName(action.keyCode))
            is MappedAction.MouseClick -> parts.add(action.button.toDisplayName())
            is MappedAction.SwitchLayer -> parts.add("切换→${action.layerName}")
            is MappedAction.MouseMove -> parts.add("鼠标移动")
            is MappedAction.LookAround -> parts.add("视角控制")
            is MappedAction.MouseToggle -> parts.add("长按${action.button.toDisplayName()}")
        }
        subCommands.forEach { parts.add(keyCodeToName(it)) }
        return parts.joinToString("+")
    }

    companion object {
        /** 子命令最大数量 */
        const val MAX_SUB_COMMANDS = 3

        /**
         * Android KeyCode 转换为人类可读名称
         *
         * 覆盖字母/数字/功能键/修饰键/符号键等常用按键。
         * 使用 Android [KeyEvent] 常量值，但直接使用整数避免测试时依赖 Android 框架。
         *
         * ## Android KeyCode 常用值
         * - A=29, B=30, ..., Z=54
         * - 0=7, 1=8, ..., 9=16
         * - F1=131, F2=132, ..., F12=142
         * - Shift=59/60, Ctrl=113/114, Alt=57/58
         * - Space=62, Enter=66, Tab=61, Esc=111
         */
        fun keyCodeToName(keyCode: Int): String {
            // 字母 A-Z (KEYCODE_A=29 ~ KEYCODE_Z=54)
            if (keyCode in 29..54) {
                return ('A' + (keyCode - 29)).toString()
            }
            // 数字 0-9 (KEYCODE_0=7 ~ KEYCODE_9=16)
            if (keyCode in 7..16) {
                return ('0' + (keyCode - 7)).toString()
            }
            // 功能键 F1-F12 (KEYCODE_F1=131 ~ KEYCODE_F12=142)
            if (keyCode in 131..142) {
                return "F${keyCode - 131 + 1}"
            }
            // 其他常用键
            return when (keyCode) {
                62 -> "Space"           // KEYCODE_SPACE
                66 -> "Enter"           // KEYCODE_ENTER
                61 -> "Tab"             // KEYCODE_TAB
                111 -> "Esc"            // KEYCODE_ESCAPE
                4 -> "Back"             // KEYCODE_BACK
                67 -> "Backspace"       // KEYCODE_DEL
                59, 60 -> "Shift"        // KEYCODE_SHIFT_LEFT/RIGHT
                113, 114 -> "Ctrl"       // KEYCODE_CTRL_LEFT/RIGHT
                57, 58 -> "Alt"          // KEYCODE_ALT_LEFT/RIGHT
                19 -> "↑"               // KEYCODE_DPAD_UP
                20 -> "↓"               // KEYCODE_DPAD_DOWN
                21 -> "←"               // KEYCODE_DPAD_LEFT
                22 -> "→"               // KEYCODE_DPAD_RIGHT
                69 -> "-"               // KEYCODE_MINUS
                70 -> "="               // KEYCODE_EQUALS
                71 -> "["               // KEYCODE_LEFT_BRACKET
                72 -> "]"               // KEYCODE_RIGHT_BRACKET
                74 -> ";"               // KEYCODE_SEMICOLON
                75 -> "'"               // KEYCODE_APOSTROPHE
                73 -> "\\"              // KEYCODE_BACKSLASH
                55 -> ","               // KEYCODE_COMMA
                56 -> "."               // KEYCODE_PERIOD
                76 -> "/"               // KEYCODE_SLASH
                68 -> "`"               // KEYCODE_GRAVE
                115 -> "CapsLock"       // KEYCODE_CAPS_LOCK
                143 -> "NumLock"        // KEYCODE_NUM_LOCK
                116 -> "ScrollLock"     // KEYCODE_SCROLL_LOCK
                124 -> "Insert"         // KEYCODE_INSERT
                123 -> "Home"           // KEYCODE_HOME
                92 -> "PageUp"          // KEYCODE_PAGE_UP
                93 -> "PageDown"        // KEYCODE_PAGE_DOWN
                122 -> "End"            // KEYCODE_MOVE_END
                144 -> "Num0"           // KEYCODE_NUMPAD_0
                145 -> "Num1"           // KEYCODE_NUMPAD_1
                146 -> "Num2"           // KEYCODE_NUMPAD_2
                147 -> "Num3"           // KEYCODE_NUMPAD_3
                148 -> "Num4"           // KEYCODE_NUMPAD_4
                149 -> "Num5"           // KEYCODE_NUMPAD_5
                150 -> "Num6"           // KEYCODE_NUMPAD_6
                151 -> "Num7"           // KEYCODE_NUMPAD_7
                152 -> "Num8"           // KEYCODE_NUMPAD_8
                153 -> "Num9"           // KEYCODE_NUMPAD_9
                else -> "Key($keyCode)"
            }
        }
    }
}

/**
 * 操作层
 *
 * 一个操作层包含自己的按键映射表。当层激活时，按键查询优先使用本层映射，
 * 本层没有的按键回退到公共层（[ControllerProfile.commonLayer]）。
 *
 * ## 层类型
 * - **公共层** (commonLayer): 名称为 "Common"，始终激活，[triggerButton] 为 null
 * - **操作层 1-10**: 各有 [triggerButton] 触发按键，按住激活、松开回公共层
 *
 * ## 触发按键机制
 * 每个操作层可设置一个 [ControllerButton] 作为触发按键。
 * 当该按键被按下时，激活对应操作层；松开时，回到公共层。
 * 例如: Layer1 的 triggerButton = DPAD_UP，按住 D-Pad 上 → 激活 Layer1，松开 → 回 Common
 *
 * @param name 层名称（如 "Common"、"Layer1"、"战斗"）
 * @param triggerButton 触发按键（公共层为 null）
 * @param buttonMappings 按键映射表（ControllerButton → KeyMapping）
 */
data class OperationLayer(
    val name: String,
    val triggerButton: ControllerButton? = null,
    val buttonMappings: MutableMap<ControllerButton, KeyMapping> = mutableMapOf()
) {
    /**
     * 查询本层是否有指定按钮的映射
     */
    fun getMapping(button: ControllerButton): KeyMapping? = buttonMappings[button]
}

/**
 * 全局设置（所有层统一）
 *
 * 摇杆相关参数统一配置，不区分操作层。
 *
 * @param deadzone 摇杆死区（0.0~1.0），小于此值的输入归零。默认 0.15
 * @param lookSensitivity 右摇杆视角灵敏度倍率（0.1~5.0），越大移动越快。默认 0.5
 * @param cursorSpeed 光标移动速度倍率（>1.0 更快，<1.0 更慢）。默认 1.0
 * @param lookSmoothing 视角平滑系数（0.0~0.95），0=关闭平滑，越大越顺滑但延迟略增。默认 0.5
 * @param lookAcceleration 视角加速曲线指数（0.5~3.0），1.0=线性，>1轻推更慢重推更快，<1相反。默认 1.5
 */
data class GlobalSettings(
    val deadzone: Float = 0.15f,
    val lookSensitivity: Float = 0.5f,
    val cursorSpeed: Float = 1.0f,
    val lookSmoothing: Float = 0.5f,
    val lookAcceleration: Float = 1.5f
)

/**
 * 控制器完整配置
 *
 * 包含公共层、10 个操作层和全局设置，对应一个完整的配置文件。
 *
 * @param commonLayer 公共层（始终激活）
 * @param layers 操作层列表（最多 10 个）
 * @param globalSettings 全局摇杆设置
 */
data class ControllerProfile(
    val commonLayer: OperationLayer,
    val layers: List<OperationLayer>,
    val globalSettings: GlobalSettings = GlobalSettings()
) {
    /**
     * 所有层（公共层 + 操作层），用于 UI 显示
     */
    val allLayers: List<OperationLayer> get() = listOf(commonLayer) + layers

    /**
     * 按名称查找操作层
     */
    fun findLayer(name: String): OperationLayer? =
        allLayers.firstOrNull { it.name == name }

    /**
     * 按触发按键查找操作层
     *
     * @param button 手柄按键
     * @return 对应的操作层；公共层和未配置的返回 null
     */
    fun findLayerByTrigger(button: ControllerButton): OperationLayer? =
        layers.firstOrNull { it.triggerButton == button }

    companion object {
        /** 操作层最大数量 */
        const val MAX_LAYERS = 10

        /**
         * 创建默认配置（10 个操作层，Common 层配置触发按键映射）
         *
         * 层切换通过 Common 层的 KeyMapping(SwitchLayer) 实现：
         * - 按住触发键 → 激活对应操作层（按键映射优先用激活层，回退到 Common）
         * - 松开触发键 → 停用对应操作层，回到公共层
         *
         * 默认 Common 层层切换映射:
         * - D-Pad ↑ → Layer1
         * - D-Pad ↓ → Layer2
         * - D-Pad ← → Layer3
         * - D-Pad → → Layer4
         * - LB → Layer5
         * - RB → Layer6
         * - L3 → Layer7
         * - R3 → Layer8
         * - L2 → Layer9
         * - R2 → Layer10
         *
         * 操作层的 [OperationLayer.triggerButton] 仅用于显示，不再用于自动激活。
         */
        fun createDefault(): ControllerProfile {
            // Common 层层切换按键映射（保留显示用的 triggerButton 与之一致）
            // 注意：RIGHT_STICK_CLICK (R3) 保留为 LookAround 视角控制，不作为层切换键
            // Layer8 的触发键改为 TOUCHPAD_CLICK，避免覆盖 R3 的 LookAround 映射
            val triggerButtons = listOf(
                ControllerButton.DPAD_UP to "Layer1",
                ControllerButton.DPAD_DOWN to "Layer2",
                ControllerButton.DPAD_LEFT to "Layer3",
                ControllerButton.DPAD_RIGHT to "Layer4",
                ControllerButton.LEFT_SHOULDER to "Layer5",
                ControllerButton.RIGHT_SHOULDER to "Layer6",
                ControllerButton.LEFT_STICK_CLICK to "Layer7",
                ControllerButton.TOUCHPAD_CLICK to "Layer8",
                ControllerButton.LEFT_TRIGGER_CLICK to "Layer9",
                ControllerButton.RIGHT_TRIGGER_CLICK to "Layer10"
            )

            val common = OperationLayer(name = "Common")
            // 公共层默认按键映射（除层切换外的按键）
            common.buttonMappings[ControllerButton.A] = KeyMapping(MappedAction.KeyboardKey(62))  // KEYCODE_SPACE
            common.buttonMappings[ControllerButton.B] = KeyMapping(MappedAction.MouseClick(MouseButton.RIGHT))
            common.buttonMappings[ControllerButton.X] = KeyMapping(MappedAction.MouseClick(MouseButton.LEFT))
            common.buttonMappings[ControllerButton.Y] = KeyMapping(MappedAction.KeyboardKey(37))  // KEYCODE_I
            common.buttonMappings[ControllerButton.MENU] = KeyMapping(MappedAction.KeyboardKey(111))  // KEYCODE_ESCAPE
            common.buttonMappings[ControllerButton.OPTIONS] = KeyMapping(MappedAction.KeyboardKey(41))  // KEYCODE_M
            common.buttonMappings[ControllerButton.RIGHT_STICK_CLICK] = KeyMapping(MappedAction.LookAround)
            // 层切换按键映射（按住激活对应层，松开回公共层）
            triggerButtons.forEach { (button, layerName) ->
                common.buttonMappings[button] = KeyMapping(MappedAction.SwitchLayer(layerName))
            }
            // 右摇杆默认视角控制
            // 摇杆映射不在 buttonMappings 中，而是通过 ControllerStick 单独处理

            // 操作层：triggerButton 仅用于 UI 显示，实际切换由 Common 层的 SwitchLayer 映射完成
            val layers = (1..MAX_LAYERS).mapIndexed { index, i ->
                OperationLayer(
                    name = "Layer$i",
                    triggerButton = triggerButtons[index].first
                )
            }

            return ControllerProfile(
                commonLayer = common,
                layers = layers,
                globalSettings = GlobalSettings()
            )
        }
    }
}
