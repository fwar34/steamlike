package com.steamlike.controller.core

/**
 * Steam风格输入动作定义
 *
 * 输入动作是将手柄的原始输入（按钮/摇杆/扳机）抽象为具有语义的动作。
 * 每个动作有名称和回调，当输入状态变化时触发对应回调。
 *
 * ## 三种动作类型
 *
 * - [ButtonAction]: 二进制输入（按下/释放），如 A/B/X/Y 按钮
 * - [AnalogTriggerAction]: 模拟量输入（0.0~1.0），如 LT/RT 扳机
 * - [StickPadGyroAction]: 2D向量输入，如左/右摇杆
 *
 * ## 使用流程
 *
 * 1. 在公共层中创建动作并设置回调:
 *    ```
 *    commonLayer.addButtonAction("Jump") {
 *        onPressed = { injector.sendKeyPress(KEYCODE_SPACE) }
 *    }
 *    ```
 * 2. 绑定手柄按键到动作:
 *    ```
 *    steamInput.bindButton(ControllerButton.A, "Jump")
 *    ```
 * 3. 操作层可覆盖绑定:
 *    ```
 *    combatLayer.overrideButtonBinding(ControllerButton.A, "Slot5")
 *    ```
 */
sealed class InputAction(
    open val name: String,
    open val type: InputActionType
) {
    /**
     * 按钮动作: 二进制开关输入
     *
     * 用于手柄上的数字按钮（A/B/X/Y/LB/RB/D-Pad等）。
     *
     * ## 回调触发时机
     * - [onPressed]: 按钮从释放→按下时触发一次
     * - [onReleased]: 按钮从按下→释放时触发一次
     * - [onUpdate]: 每帧调用（60fps），用于检测长按
     *
     * ## 防重复机制
     * - 如果按钮已按下且再次收到按下事件，不会重复触发 onPressed
     * - 如果按钮已释放且再次收到释放事件，不会重复触发 onReleased
     *
     * @param name 动作名称
     * @param onPressed 按下回调
     * @param onReleased 释放回调
     * @param onUpdate 每帧更新回调（isHeld=是否按住, heldTimeMs=按住时长ms）
     * @param isPressed 当前是否按下（内部状态）
     * @param heldTimeMs 按住时长（毫秒，每帧累加）
     */
    data class ButtonAction(
        override val name: String,
        var onPressed: (() -> Unit)? = null,
        var onReleased: (() -> Unit)? = null,
        var onUpdate: ((isHeld: Boolean, heldTimeMs: Long) -> Unit)? = null,
        var isPressed: Boolean = false,
        var heldTimeMs: Long = 0
    ) : InputAction(name, InputActionType.BUTTON)

    /**
     * 模拟扳机动作: 0.0 ~ 1.0
     *
     * 用于手柄的模拟扳机（LT/RT），支持按压阈值检测。
     *
     * ## 工作原理
     * - 扳机的原始值范围为 0.0（完全释放）到 1.0（完全按下）
     * - 当值超过 [pressThreshold] 时，触发 [onPressed]
     * - 当值低于 [pressThreshold] 时，触发 [onReleased]
     * - [onValueChanged] 在每次值变化时触发（用于需要连续控制的场景）
     *
     * ## 阈值的作用
     * - 防止轻微触碰导致误触发
     * - 可在操作层中覆盖（如 Aim 层将阈值从 0.3 提高到 0.5）
     *
     * @param name 动作名称
     * @param pressThreshold 按压阈值（0.0~1.0，默认0.5）
     * @param onValueChanged 值变化回调（连续触发）
     * @param onPressed 超过阈值时回调
     * @param onReleased 低于阈值时回调
     * @param currentValue 当前值（0.0~1.0）
     * @param isPressed 当前是否超过阈值（内部状态）
     */
    data class AnalogTriggerAction(
        override val name: String,
        var pressThreshold: Float = 0.5f,
        var onValueChanged: ((value: Float) -> Unit)? = null,
        var onPressed: (() -> Unit)? = null,
        var onReleased: (() -> Unit)? = null,
        var currentValue: Float = 0f,
        var isPressed: Boolean = false
    ) : InputAction(name, InputActionType.ANALOG_TRIGGER)

    /**
     * 摇杆/触控板/陀螺仪动作: 2D向量
     *
     * 用于手柄的摇杆（左/右），支持死区和响应曲线。
     *
     * ## 数据处理流程
     * 1. 原始值 [rawValue] 从手柄读取（-1.0~1.0 的2D向量）
     * 2. 在 [ActionSet.updateAll] 中应用死区和响应曲线:
     *    - 死区: 小于死区的输入归零（防止摇杆漂移）
     *    - 响应曲线: 对幅度进行指数变换
     * 3. 处理后的值存入 [currentValue]
     * 4. 触发 [onValueChanged] 回调
     * 5. 计算8方向并触发 [onDirectionChanged] 回调（方向变化时）
     *
     * ## 死区 (deadzone)
     * - 范围: 0.0~1.0
     * - 作用: 摇杆中心附近的小幅移动被忽略
     * - 典型值: 0.15~0.30
     *
     * ## 响应曲线 (responseCurve)
     * - 对幅度进行 `mag^responseCurve` 变换
     * - >1.0: 前半段更慢（精细控制，适合瞄准）
     * - <1.0: 前半段更快（快速响应，适合战斗）
     * - =1.0: 线性
     *
     * @param name 动作名称
     * @param deadzone 死区（0.0~1.0，默认0.15）
     * @param responseCurve 响应曲线指数（默认1.0=线性）
     * @param onValueChanged 值变化回调（处理后的值）
     * @param onDirectionChanged 8方向变化回调
     * @param clickAction 摇杆按下动作（如 L3/R3）
     * @param currentValue 当前处理后的值
     * @param rawValue 原始值（未经死区/曲线处理）
     */
    data class StickPadGyroAction(
        override val name: String,
        var deadzone: Float = 0.15f,
        var responseCurve: Float = 1.0f,
        var onValueChanged: ((vector: Vector2) -> Unit)? = null,
        var onDirectionChanged: ((direction: StickDirection) -> Unit)? = null,
        var clickAction: ButtonAction? = null,
        var currentValue: Vector2 = Vector2.ZERO,
        var rawValue: Vector2 = Vector2.ZERO
    ) : InputAction(name, InputActionType.STICK_PAD_GYRO) {

        /**
         * 摇杆8方向枚举
         *
         * 将摇杆的连续角度离散化为9个状态（8方向 + 中心），
         * 每45度为一个方向区间。
         */
        enum class StickDirection {
            CENTER,                                           // 中心（归零）
            UP, UP_RIGHT, RIGHT, DOWN_RIGHT,                  // 上、右上、右、右下
            DOWN, DOWN_LEFT, LEFT, UP_LEFT                    // 下、左下、左、左上
        }
    }
}
