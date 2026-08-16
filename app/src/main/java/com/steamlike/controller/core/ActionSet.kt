package com.steamlike.controller.core

/**
 * 动作集合容器
 *
 * 当前作为 **公共层 (commonLayer)** 使用，是整个输入系统的基石。
 *
 * ## 职责
 * - 定义所有可能的输入动作（按钮/摇杆/扳机）及其回调
 * - 存储默认的按键绑定（哪个手柄按键 → 哪个动作）
 * - 在60fps更新循环中处理动作状态变化（长按检测、摇杆响应曲线等）
 *
 * ## 与操作层的关系
 * 操作层（[ActionSetLayer]）不拥有自己的动作定义，而是复用本容器中的动作。
 * 操作层仅通过 `buttonBindingOverrides` 来改变"哪个按钮触发哪个动作"，
 * 以及通过 `stickOverrides`/`triggerOverrides` 来修改动作的属性（如死区、响应曲线）。
 *
 * 示例:
 * ```
 * // 1. 在公共层中定义动作
 * commonLayer.addButtonAction("Jump") {
 *     onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_SPACE) }
 * }
 * commonLayer.addButtonAction("Slot5") {
 *     onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_5) }
 * }
 *
 * // 2. 在公共层中设置默认绑定
 * steamInput.bindButton(ControllerButton.A, "Jump")  // A键默认 → Jump
 *
 * // 3. 操作层覆盖绑定（Combat层中A键 → Slot5）
 * combatLayer.overrideButtonBinding(ControllerButton.A, "Slot5")
 * ```
 *
 * @param name 容器名称（公共层使用 "__common__"）
 * @param displayName 显示名称
 */
class ActionSet(
    val name: String,
    val displayName: String = name
) {
    // ====================================================================
    // 动作定义
    // ====================================================================

    /**
     * 按钮动作集合（按名称索引）
     *
     * 按钮是二进制输入（按下/释放），如 A/B/X/Y/LB/RB/D-Pad 等。
     * 每个动作包含 onPressed/onReleased/onUpdate 回调。
     */
    val buttonActions: MutableMap<String, InputAction.ButtonAction> = mutableMapOf()

    /**
     * 扳机动作集合（按名称索引）
     *
     * 扳机是模拟量输入（0.0~1.0），如 LT/RT。
     * 当值超过 [InputAction.AnalogTriggerAction.pressThreshold] 时触发 onPressed。
     */
    val triggerActions: MutableMap<String, InputAction.AnalogTriggerAction> = mutableMapOf()

    /**
     * 摇杆动作集合（按名称索引）
     *
     * 摇杆是2D向量输入，如左摇杆/右摇杆。
     * 支持 deadzone（死区）和 responseCurve（响应曲线）配置。
     */
    val stickActions: MutableMap<String, InputAction.StickPadGyroAction> = mutableMapOf()

    // ====================================================================
    // 按键绑定
    // ====================================================================

    /**
     * 按钮绑定表: ControllerButton → ActionName
     *
     * 定义哪个手柄按钮触发哪个动作（无修饰键的默认绑定）。
     * 操作层通过 buttonBindingOverrides 覆盖此表中的绑定。
     */
    val buttonBindings: MutableMap<ControllerButton, String> = mutableMapOf()

    /**
     * 组合键绑定列表 (Chord Bindings)
     *
     * 参考 Steam Input 的子指令机制，允许同一按钮在不同修饰键下触发不同动作。
     * 例如: A → Jump, A+LB → Slot5, A+LB+LT → Potion
     *
     * 查找优先级: chordSize 大的优先（最具体的匹配）
     */
    val chordBindings: MutableList<ChordBinding> = mutableListOf()

    /**
     * 摇杆绑定表: ControllerStick → ActionName
     *
     * 定义哪个手柄摇杆驱动哪个摇杆动作。
     */
    val stickBindings: MutableMap<ControllerStick, String> = mutableMapOf()

    /**
     * 扳机绑定表: ControllerTrigger → ActionName
     *
     * 定义哪个手柄扳机驱动哪个扳机动作。
     */
    val triggerBindings: MutableMap<ControllerTrigger, String> = mutableMapOf()

    // ====================================================================
    // 生命周期回调
    // ====================================================================

    /** 容器激活时的回调（公共层始终激活，不会触发） */
    var onActivated: (() -> Unit)? = null

    /** 容器停用时的回调（公共层始终激活，不会触发） */
    var onDeactivated: (() -> Unit)? = null

    /** 内部: 是否处于激活状态 */
    internal var isActive: Boolean = false

    // ====================================================================
    // 动作注册 API
    // ====================================================================

    /**
     * 注册一个按钮动作
     *
     * @param name 动作名称（唯一标识，如 "Jump"）
     * @param block 配置块，可设置 onPressed/onReleased/onUpdate 回调
     * @return 创建的按钮动作实例
     *
     * 示例:
     * ```
     * commonLayer.addButtonAction("Jump") {
     *     onPressed = { println("跳跃!") }
     *     onUpdate = { held, timeMs ->
     *         if (held && timeMs > 500) println("长按跳跃")
     *     }
     * }
     * ```
     */
    fun addButtonAction(
        name: String,
        block: InputAction.ButtonAction.() -> Unit = {}
    ): InputAction.ButtonAction {
        return InputAction.ButtonAction(name).apply(block).also { buttonActions[name] = it }
    }

    /**
     * 注册一个扳机动作
     *
     * @param name 动作名称（如 "Cast"）
     * @param block 配置块，可设置 pressThreshold/onPressed/onReleased 等
     * @return 创建的扳机动作实例
     *
     * 示例:
     * ```
     * commonLayer.addTriggerAction("Cast") {
     *     pressThreshold = 0.3f  // 扳机按压30%即触发
     *     onPressed = { injector.sendMouseDown(MouseButton.LEFT) }
     *     onReleased = { injector.sendMouseUp(MouseButton.LEFT) }
     * }
     * ```
     */
    fun addTriggerAction(
        name: String,
        block: InputAction.AnalogTriggerAction.() -> Unit = {}
    ): InputAction.AnalogTriggerAction {
        return InputAction.AnalogTriggerAction(name).apply(block).also { triggerActions[name] = it }
    }

    /**
     * 注册一个摇杆动作
     *
     * @param name 动作名称（如 "Move"）
     * @param block 配置块，可设置 deadzone/responseCurve/onValueChanged 等
     * @return 创建的摇杆动作实例
     *
     * 示例:
     * ```
     * commonLayer.addStickAction("Move") {
     *     deadzone = 0.2f       // 20%死区，防止漂移
     *     responseCurve = 1.3f  // 指数曲线，轻微推动时更精细
     *     onValueChanged = { v -> handleMove(v) }
     * }
     * ```
     */
    fun addStickAction(
        name: String,
        block: InputAction.StickPadGyroAction.() -> Unit = {}
    ): InputAction.StickPadGyroAction {
        return InputAction.StickPadGyroAction(name).apply(block).also { stickActions[name] = it }
    }

    /**
     * 添加组合键绑定 (Chord Binding)
     *
     * 参考 Steam Input 的子指令机制，允许同一按钮在不同修饰键下触发不同动作。
     *
     * @param button 触发按钮
     * @param actionName 动作名称
     * @param chord 需要同时按住的修饰按钮集合
     * @return 创建的 [ChordBinding] 实例
     *
     * 示例:
     * ```
     * // A 单独按下 → Jump
     * commonLayer.addChordBinding(ControllerButton.A, "Jump")
     * // A + LB → Slot5
     * commonLayer.addChordBinding(ControllerButton.A, "Slot5", setOf(ControllerButton.LEFT_SHOULDER))
     * // A + LB + LT → Potion
     * commonLayer.addChordBinding(ControllerButton.A, "Potion",
     *     setOf(ControllerButton.LEFT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK))
     * ```
     */
    fun addChordBinding(
        button: ControllerButton,
        actionName: String,
        chord: Set<ControllerButton> = emptySet()
    ): ChordBinding {
        return ChordBinding(button, actionName, chord).also { chordBindings.add(it) }
    }

    // ====================================================================
    // 生命周期管理（内部）
    // ====================================================================

    /** 激活容器 */
    internal fun activate() {
        if (!isActive) {
            isActive = true
            onActivated?.invoke()
        }
    }

    /** 停用容器并重置所有动作状态 */
    internal fun deactivate() {
        if (isActive) {
            isActive = false
            resetState()
            onDeactivated?.invoke()
        }
    }

    /**
     * 重置所有动作状态
     *
     * 在停用时调用，确保所有按钮/摇杆/扳机回到初始状态，
     * 并触发摇杆的 onValueChanged(ZERO) 回调以释放相关按键。
     */
    internal fun resetState() {
        buttonActions.values.forEach { it.isPressed = false; it.heldTimeMs = 0 }
        triggerActions.values.forEach { it.currentValue = 0f; it.isPressed = false }
        stickActions.values.forEach {
            it.currentValue = Vector2.ZERO
            it.rawValue = Vector2.ZERO
            it.onValueChanged?.invoke(Vector2.ZERO)  // 通知释放
        }
    }

    // ====================================================================
    // 更新循环（每帧调用）
    // ====================================================================

    /**
     * 每帧更新所有动作的状态
     *
     * 由 [SteamInput] 的60fps更新循环调用，负责:
     * - 按钮动作: 累加 heldTimeMs，触发 onUpdate 回调（长按检测）
     * - 扳机动作: 检测值是否跨越 pressThreshold，触发 onPressed/onReleased
     * - 摇杆动作: 应用死区和响应曲线，触发 onValueChanged/onDirectionChanged
     *
     * @param deltaTimeMs 距离上一帧的时间间隔（毫秒）
     */
    internal fun updateAll(deltaTimeMs: Long) {
        // 按钮动作: 累加按住时长，触发 onUpdate
        buttonActions.values.forEach { action ->
            if (action.isPressed) action.heldTimeMs += deltaTimeMs
            action.onUpdate?.invoke(action.isPressed, action.heldTimeMs)
        }

        // 扳机动作: 检测按压阈值跨越
        triggerActions.values.forEach { action ->
            val shouldBePressed = action.currentValue >= action.pressThreshold
            if (shouldBePressed != action.isPressed) {
                action.isPressed = shouldBePressed
                if (shouldBePressed) action.onPressed?.invoke() else action.onReleased?.invoke()
            }
        }

        // 摇杆动作: 应用死区和响应曲线
        stickActions.values.forEach { action ->
            applyStickResponseCurve(action)
        }
    }

    /**
     * 对摇杆原始值应用死区和响应曲线
     *
     * 处理流程:
     * 1. 应用死区: 小于死区的输入被视为零（防止摇杆漂移）
     * 2. 应用响应曲线: 对幅度进行指数变换（>1=前半段更慢, <1=前半段更快）
     * 3. 触发 onValueChanged 回调
     * 4. 计算8方向并触发 onDirectionChanged 回调（方向变化时）
     *
     * @param action 摇杆动作
     */
    private fun applyStickResponseCurve(action: InputAction.StickPadGyroAction) {
        val raw = action.rawValue
        val withDeadzone = raw.withDeadzone(action.deadzone)
        val mag = withDeadzone.magnitude

        if (mag <= 0f) {
            // 归零时也触发回调，确保键鼠映射能正确释放按键
            action.currentValue = Vector2.ZERO
            action.onValueChanged?.invoke(action.currentValue)
            notifyDirectionChanged(action, InputAction.StickPadGyroAction.StickDirection.CENTER)
            return
        }

        // 响应曲线: mag^responseCurve
        // - responseCurve > 1: 前半段更慢（精细控制）
        // - responseCurve < 1: 前半段更快（快速响应）
        // - responseCurve = 1: 线性
        val curvedMag = Math.pow(mag.toDouble(), action.responseCurve.toDouble()).toFloat().coerceIn(0f, 1f)
        val norm = withDeadzone.normalized()
        action.currentValue = Vector2(norm.x * curvedMag, norm.y * curvedMag)
        action.onValueChanged?.invoke(action.currentValue)

        // 计算并通知8方向变化
        val dir = calculate8Direction(action.currentValue)
        notifyDirectionChanged(action, dir)
    }

    /**
     * 根据摇杆向量计算8方向枚举
     *
     * 将摇杆的连续角度离散化为8个方向（每45度一个）:
     * CENTER / UP / UP_RIGHT / RIGHT / DOWN_RIGHT / DOWN / DOWN_LEFT / LEFT / UP_LEFT
     *
     * @param v 摇杆向量
     * @return 方向枚举
     */
    private fun calculate8Direction(v: Vector2): InputAction.StickPadGyroAction.StickDirection {
        if (v.magnitude < 0.1f) return InputAction.StickPadGyroAction.StickDirection.CENTER
        val angle = kotlin.math.atan2(v.y.toDouble(), v.x.toDouble())
        val deg = Math.toDegrees(angle)
        return when {
            deg >= -22.5 && deg < 22.5 -> InputAction.StickPadGyroAction.StickDirection.RIGHT
            deg >= 22.5 && deg < 67.5 -> InputAction.StickPadGyroAction.StickDirection.DOWN_RIGHT
            deg >= 67.5 && deg < 112.5 -> InputAction.StickPadGyroAction.StickDirection.DOWN
            deg >= 112.5 && deg < 157.5 -> InputAction.StickPadGyroAction.StickDirection.DOWN_LEFT
            deg >= 157.5 || deg < -157.5 -> InputAction.StickPadGyroAction.StickDirection.LEFT
            deg >= -157.5 && deg < -112.5 -> InputAction.StickPadGyroAction.StickDirection.UP_LEFT
            deg >= -112.5 && deg < -67.5 -> InputAction.StickPadGyroAction.StickDirection.UP
            deg >= -67.5 && deg < -22.5 -> InputAction.StickPadGyroAction.StickDirection.UP_RIGHT
            else -> InputAction.StickPadGyroAction.StickDirection.CENTER
        }
    }

    /** 记录每个摇杆动作上一次的方向，用于检测方向变化 */
    private val lastDirectionMap = mutableMapOf<String, InputAction.StickPadGyroAction.StickDirection>()

    /**
     * 通知摇杆方向变化（仅在方向改变时触发回调）
     *
     * @param action 摇杆动作
     * @param newDir 新方向
     */
    private fun notifyDirectionChanged(
        action: InputAction.StickPadGyroAction,
        newDir: InputAction.StickPadGyroAction.StickDirection
    ) {
        val lastDir = lastDirectionMap[action.name]
        if (lastDir != newDir) {
            lastDirectionMap[action.name] = newDir
            action.onDirectionChanged?.invoke(newDir)
        }
    }
}
