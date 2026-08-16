package com.steamlike.controller.core

/**
 * 操作层 (Action Set Layer)
 *
 * 可叠加于公共层(commonLayer)之上的可选绑定覆盖集。
 *
 * ## 设计理念
 *
 * 操作层不会完全替换公共层的绑定，而是做 **增量覆盖**:
 * - 只覆盖需要修改的按键绑定和动作属性
 * - 未覆盖的按键继续使用公共层的默认绑定
 *
 * 这意味着每个操作层"继承"公共层的全部按键映射，并可单独定制任意子集。
 *
 * ## 多层叠加
 *
 * 可以同时激活多个操作层，按激活顺序形成栈:
 * - 后激活的层在栈顶，优先级更高
 * - 按键查找时从栈顶到栈底遍历，第一个找到的覆盖生效
 * - 如果所有层都没有覆盖某个按键，回退到公共层
 *
 * 示例:
 * ```
 * 公共层: A → Jump
 * Combat层: A → Slot5 (激活)
 * Aim层: (未覆盖A) (激活)
 *
 * 查找A的绑定:
 *   栈顶 Aim层 → 未覆盖A → 继续
 *   栈底 Combat层 → 覆盖A为Slot5 → 生效!
 *   结果: A → Slot5
 * ```
 *
 * ## 覆盖类型
 *
 * 1. **绑定覆盖** (buttonBindingOverrides): 改变"哪个按钮→哪个动作"
 *    - 例如: Combat层将 A 从 "Jump" 改为 "Slot5"
 *
 * 2. **属性覆盖** (stickOverrides/triggerOverrides):
 *    修改动作的属性（不改绑定），如摇杆死区、响应曲线、扳机阈值等
 *    - 例如: Aim层将 "Look" 摇杆的死区从 0.15 改为 0.25
 *
 * ## 可序列化
 *
 * 覆盖属性使用显式数据类 [StickOverride] / [TriggerOverride] 存储，
 * 而非 lambda 闭包，因此可以完整导出/导入到 JSON 配置文件。
 *
 * @param name 层的唯一标识名（如 "Combat"）
 * @param displayName 显示名称（如 "战斗模式"）
 */
class ActionSetLayer(
    val name: String,
    val displayName: String = name
) {
    // ====================================================================
    // 绑定覆盖: 改变"哪个按钮 → 哪个动作"
    // ====================================================================

    /**
     * 按钮绑定覆盖表: ControllerButton → 新的ActionName
     *
     * 当查找按钮绑定时，优先检查此表。
     * 如果按钮在此表中有记录，则使用覆盖的动作名称，而非公共层的默认绑定。
     *
     * 示例:
     * ```
     * // Combat层: A键从"Jump"改为"Slot5"
     * overrideButtonBinding(ControllerButton.A, "Slot5")
     * ```
     */
    val buttonBindingOverrides: MutableMap<ControllerButton, String> = mutableMapOf()

    // ====================================================================
    // 属性覆盖: 修改动作的属性（不改绑定）
    // ====================================================================

    /**
     * 扳机动作属性覆盖表: ActionName → [TriggerOverride]
     *
     * 修改指定扳机动作的属性，如 pressThreshold（按压阈值）。
     *
     * 示例:
     * ```
     * // Aim层: Cast扳机的按压阈值从0.3改为0.5（需要更用力按压）
     * overrideTrigger("Cast") {
     *     pressThreshold = 0.5f
     * }
     * ```
     */
    val triggerOverrides: MutableMap<String, TriggerOverride> = mutableMapOf()

    /**
     * 摇杆动作属性覆盖表: ActionName → [StickOverride]
     *
     * 修改指定摇杆动作的属性，如 deadzone（死区）和 responseCurve（响应曲线）。
     *
     * 示例:
     * ```
     * // Aim层: Look摇杆的死区更大、曲线更平（精确瞄准）
     * overrideStick("Look") {
     *     deadzone = 0.25f
     *     responseCurve = 0.5f
     * }
     * ```
     */
    val stickOverrides: MutableMap<String, StickOverride> = mutableMapOf()

    // ====================================================================
    // 生命周期回调
    // ====================================================================

    /** 层被激活（加入栈）时的回调 */
    var onActivated: (() -> Unit)? = null

    /** 层被停用（从栈移除）时的回调 */
    var onDeactivated: (() -> Unit)? = null

    /** 该层在活跃栈中的位置索引（-1=未激活, 0=栈底, size-1=栈顶） */
    internal var stackPosition: Int = -1

    // ====================================================================
    // 绑定覆盖 API
    // ====================================================================

    /**
     * 覆盖按钮绑定（改变"哪个按钮→哪个动作"）
     *
     * @param button 手柄按钮
     * @param actionName 新的动作名称（需在公共层中已定义）
     *
     * 示例:
     * ```
     * // Combat层: A键 → Slot5动作（而非公共层的Jump）
     * overrideButtonBinding(ControllerButton.A, "Slot5")
     * ```
     */
    fun overrideButtonBinding(button: ControllerButton, actionName: String) {
        buttonBindingOverrides[button] = actionName
    }

    // ====================================================================
    // 属性覆盖 API
    // ====================================================================

    /**
     * 覆盖摇杆动作的属性
     *
     * @param actionName 动作名称
     * @param block 属性修改块（可修改 deadzone/responseCurve）
     */
    fun overrideStick(
        actionName: String,
        block: StickOverride.() -> Unit
    ) {
        val existing = stickOverrides[actionName] ?: StickOverride()
        existing.apply(block)
        stickOverrides[actionName] = existing
    }

    /**
     * 覆盖扳机动作的属性
     *
     * @param actionName 动作名称
     * @param block 属性修改块（可修改 pressThreshold）
     */
    fun overrideTrigger(
        actionName: String,
        block: TriggerOverride.() -> Unit
    ) {
        val existing = triggerOverrides[actionName] ?: TriggerOverride()
        existing.apply(block)
        triggerOverrides[actionName] = existing
    }

    // ====================================================================
    // 生命周期管理（内部）
    // ====================================================================

    /**
     * 激活层（由 SteamInput 调用）
     *
     * @param position 在栈中的位置索引
     */
    internal fun activate(position: Int) {
        stackPosition = position
        onActivated?.invoke()
    }

    /** 停用层（由 SteamInput 调用） */
    internal fun deactivate() {
        stackPosition = -1
        onDeactivated?.invoke()
    }
}

/**
 * 摇杆属性覆盖
 *
 * 存储摇杆动作需要覆盖的属性值。`null` 表示不覆盖该属性（继承公共层值）。
 *
 * @param deadzone 死区覆盖值（null=不覆盖）
 * @param responseCurve 响应曲线覆盖值（null=不覆盖）
 */
data class StickOverride(
    var deadzone: Float? = null,
    var responseCurve: Float? = null
) {
    /**
     * 将覆盖应用到摇杆动作
     *
     * 仅覆盖非 null 的属性，null 属性保持动作原值不变。
     *
     * @param action 要应用覆盖的摇杆动作
     */
    fun applyTo(action: InputAction.StickPadGyroAction) {
        deadzone?.let { action.deadzone = it }
        responseCurve?.let { action.responseCurve = it }
    }
}

/**
 * 扳机属性覆盖
 *
 * 存储扳机动作需要覆盖的属性值。`null` 表示不覆盖该属性。
 *
 * @param pressThreshold 按压阈值覆盖值（null=不覆盖）
 */
data class TriggerOverride(
    var pressThreshold: Float? = null
) {
    /**
     * 将覆盖应用到扳机动作
     *
     * @param action 要应用覆盖的扳机动作
     */
    fun applyTo(action: InputAction.AnalogTriggerAction) {
        pressThreshold?.let { action.pressThreshold = it }
    }
}
