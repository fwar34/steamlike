package com.steamlike.controller.core

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 已连接的手柄设备信息
 *
 * 封装 Android [InputDevice] 的相关信息，包括设备类型识别、能力检测等。
 * 由 [SteamInput] 在设备连接时通过 [fromInputDevice] 创建，存储在 `connectedControllers` 映射中。
 *
 * ## 设备类型识别
 * 通过 USB Vendor ID 和 Product ID 识别手柄类型（Xbox/PS/Switch/Steam Controller），
 * 用于按键映射修正（如 PS 手柄的 ×/○ 与 Xbox 的 A/B 位置互换）。
 *
 * ## 能力检测
 * 检测设备支持的输入类型:
 * - 是否有左/右摇杆（通过 AXIS_X/Y/Z/RX 等检测）
 * - 是否有模拟扳机（通过 AXIS_LTRIGGER/AXIS_BRAKE 检测）
 * - 是否有十字键（通过 SOURCE_DPAD 检测）
 * - 是否支持震动（通过 Vibrator.hasVibrator() 检测）
 *
 * @param deviceId Android 设备 ID（与 InputEvent.deviceId 一致）
 * @param name 设备名称（来自 InputDevice.name）
 * @param controllerType 手柄类型（用于按键修正）
 * @param inputDevice 原始 Android InputDevice 对象
 * @param supportsVibration 是否支持震动反馈
 * @param hasLeftStick 是否有左摇杆
 * @param hasRightStick 是否有右摇杆
 * @param hasAnalogTriggers 是否有模拟扳机（LT/RT 可读取 0.0~1.0 值）
 * @param hasDpad 是否有十字键
 */
data class ControllerDevice(
    val deviceId: Int,
    val name: String,
    val controllerType: ControllerType,
    val inputDevice: InputDevice,
    val supportsVibration: Boolean,
    val hasLeftStick: Boolean,
    val hasRightStick: Boolean,
    val hasAnalogTriggers: Boolean,
    val hasDpad: Boolean
) {
    companion object {
        /**
         * 从 Android InputDevice 创建 ControllerDevice
         *
         * 流程:
         * 1. 检查设备来源（SOURCE_GAMEPAD 或 SOURCE_JOYSTICK），都不是则返回 null
         * 2. 检测摇杆、扳机、D-Pad 等能力
         * 3. 检测震动支持
         * 4. 通过 Vendor/Product ID 识别手柄类型
         *
         * @param device Android InputDevice
         * @return ControllerDevice 实例；非手柄设备返回 null
         */
        fun fromInputDevice(device: InputDevice): ControllerDevice? {
            val sources = device.sources
            // 检查是否为手柄类设备（游戏手柄或摇杆）
            val isGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            val isJoystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (!isGamepad && !isJoystick) return null

            // 检测左摇杆: AXIS_X 和 AXIS_Y 同时存在
            val hasLeftStick = device.getMotionRange(MotionEvent.AXIS_X) != null &&
                    device.getMotionRange(MotionEvent.AXIS_Y) != null
            // 检测右摇杆: AXIS_Z 或 AXIS_RX 任一存在
            val hasRightStick = device.getMotionRange(MotionEvent.AXIS_Z) != null ||
                    device.getMotionRange(MotionEvent.AXIS_RX) != null
            // 检测模拟扳机: AXIS_LTRIGGER 或 AXIS_BRAKE 任一存在
            val hasAnalogTriggers = device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null ||
                    device.getMotionRange(MotionEvent.AXIS_BRAKE) != null
            // 检测十字键
            val hasDpad = sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

            // 检测震动支持
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                device.vibrator
            } else {
                @Suppress("DEPRECATION")
                device.vibrator
            }
            val supportsVibration = vibrator?.hasVibrator() == true

            return ControllerDevice(
                deviceId = device.id,
                name = device.name,
                controllerType = ControllerType.fromVendorProduct(device.vendorId, device.productId),
                inputDevice = device,
                supportsVibration = supportsVibration,
                hasLeftStick = hasLeftStick,
                hasRightStick = hasRightStick,
                hasAnalogTriggers = hasAnalogTriggers,
                hasDpad = hasDpad
            )
        }
    }
}

/**
 * 手柄输入映射器
 *
 * 将 Android 原生 [KeyEvent] / [MotionEvent] 映射到统一的 [ControllerButton] / [Vector2] / Float。
 * 同时处理不同手柄类型的按键修正（如 PS 手柄的 ×/○ 与 Xbox 的 A/B 位置互换）。
 *
 * ## 主要功能
 * 1. **按键映射**: Android KEYCODE_BUTTON_* → ControllerButton 枚举
 * 2. **PS 手柄修正**: PS 手柄的 ×/○ 位置与 Xbox 的 A/B 互换，需要特殊处理
 * 3. **摇杆值读取**: 从 MotionEvent 的 AXIS_X/Y/Z/RX 等轴读取摇杆位置，归一化到 -1.0~1.0
 * 4. **扳机值读取**: 从 AXIS_LTRIGGER/AXIS_BRAKE 等轴读取扳机值，归一化到 0.0~1.0
 *
 * ## 使用场景
 * 在 [SteamInput.dispatchKeyEvent] 和 [SteamInput.dispatchGenericMotionEvent] 中调用，
 * 将系统事件转换为统一的内部表示后再进行动作分发。
 */
object ControllerInputMapper {

    /**
     * Android KeyCode → ControllerButton 默认映射表
     *
     * 适用于 Xbox 风格手柄（A/B/X/Y 按标准布局）。
     * PS 手柄需要额外使用 [psKeyCodeOverrides] 修正。
     */
    private val keyCodeMap: Map<Int, ControllerButton> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to ControllerButton.A,
        KeyEvent.KEYCODE_BUTTON_B to ControllerButton.B,
        KeyEvent.KEYCODE_BUTTON_X to ControllerButton.X,
        KeyEvent.KEYCODE_BUTTON_Y to ControllerButton.Y,
        KeyEvent.KEYCODE_BUTTON_L1 to ControllerButton.LEFT_SHOULDER,    // LB
        KeyEvent.KEYCODE_BUTTON_R1 to ControllerButton.RIGHT_SHOULDER,   // RB
        KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerButton.LEFT_STICK_CLICK,   // L3
        KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerButton.RIGHT_STICK_CLICK,  // R3
        KeyEvent.KEYCODE_BUTTON_L2 to ControllerButton.LEFT_TRIGGER_CLICK,     // L2 按到底
        KeyEvent.KEYCODE_BUTTON_R2 to ControllerButton.RIGHT_TRIGGER_CLICK,    // R2 按到底
        KeyEvent.KEYCODE_BUTTON_START to ControllerButton.MENU,
        KeyEvent.KEYCODE_BUTTON_SELECT to ControllerButton.OPTIONS,
        KeyEvent.KEYCODE_BUTTON_MODE to ControllerButton.GUIDE,           // Home/PS键
        KeyEvent.KEYCODE_DPAD_UP to ControllerButton.DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to ControllerButton.DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to ControllerButton.DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to ControllerButton.DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_C to ControllerButton.TOUCHPAD_CLICK      // PS 触控板点击
    )

    /**
     * PS 手柄按键覆盖表
     *
     * PS 手柄的 ×/○ 与 Xbox 的 A/B 物理位置互换:
     * - PS 的 × (Android KEYCODE_BUTTON_A) 在 Xbox 的 A 位置，但 Android 报为 A
     * - PS 的 ○ (Android KEYCODE_BUTTON_B) 在 Xbox 的 B 位置，但 Android 报为 B
     *
     * 为保持统一的游戏体验（A=确认/跳跃，B=取消），需要交换映射:
     * - Android A → ControllerButton.X（PS 的 × 映射到 Xbox 的 X，因为 × 在 PS 上是确认键，对应 Xbox 的 A）
     * - Android X → ControllerButton.A
     *
     * 注意: 这里只覆盖 A/B/X，Y 不变（Y/△ 在两种手柄上位置一致）
     */
    private val psKeyCodeOverrides: Map<Int, ControllerButton> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to ControllerButton.X,  // PS × → Xbox X
        KeyEvent.KEYCODE_BUTTON_B to ControllerButton.B,  // PS ○ → Xbox B（不变）
        KeyEvent.KEYCODE_BUTTON_X to ControllerButton.A,  // PS □ → Xbox A
        KeyEvent.KEYCODE_BUTTON_Y to ControllerButton.Y   // PS △ → Xbox Y（不变）
    )

    /**
     * 将 Android KeyCode 映射为统一的 ControllerButton
     *
     * 流程:
     * 1. 如果是 PS 手柄（PS3/PS4/PS5），先查 [psKeyCodeOverrides]
     * 2. 否则查 [keyCodeMap] 默认映射
     *
     * @param keyCode Android KeyEvent.keyCode
     * @param controllerType 手柄类型（用于决定是否应用 PS 修正）
     * @return 对应的 ControllerButton；未映射的按键返回 null
     */
    fun mapKeyCode(keyCode: Int, controllerType: ControllerType): ControllerButton? {
        // PS 手柄特殊处理: ×/○ 与 A/B 位置互换
        if (controllerType == ControllerType.PS3 || controllerType == ControllerType.PS4 ||
            controllerType == ControllerType.PS5_DUALSENSE
        ) {
            psKeyCodeOverrides[keyCode]?.let { return it }
        }
        return keyCodeMap[keyCode]
    }

    /**
     * 从 MotionEvent 读取摇杆的 2D 位置
     *
     * 不同摇杆使用不同的 MotionEvent 轴:
     * - **左摇杆**: AXIS_X (X轴), AXIS_Y (Y轴)
     * - **右摇杆**: AXIS_Z/AXIS_RX (X轴), AXIS_RZ/AXIS_RY (Y轴)（不同设备用不同轴）
     * - **D-Pad 作为摇杆**: AXIS_HAT_X, AXIS_HAT_Y（值仅 -1/0/1）
     *
     * 返回的 Vector2 范围为 -1.0~1.0，已应用死区（flat）。
     *
     * @param event MotionEvent
     * @param stick 摇杆类型
     * @param device 输入设备（用于查询轴范围）
     * @return 摇杆位置向量
     */
    fun getStickValue(event: MotionEvent, stick: ControllerStick, device: InputDevice): Vector2 {
        return when (stick) {
            ControllerStick.LEFT_STICK -> {
                val x = getCenteredAxis(event, device, MotionEvent.AXIS_X)
                val y = getCenteredAxis(event, device, MotionEvent.AXIS_Y)
                Vector2(x, y)
            }
            ControllerStick.RIGHT_STICK -> {
                // 右摇杆 X 轴: 优先 AXIS_Z，回退到 AXIS_RX
                var x = getCenteredAxis(event, device, MotionEvent.AXIS_Z)
                if (x == 0f) x = getCenteredAxis(event, device, MotionEvent.AXIS_RX)
                // 右摇杆 Y 轴: 优先 AXIS_RZ，回退到 AXIS_RY
                var y = getCenteredAxis(event, device, MotionEvent.AXIS_RZ)
                if (y == 0f) y = getCenteredAxis(event, device, MotionEvent.AXIS_RY)
                Vector2(x, y)
            }
            ControllerStick.DPAD_AS_STICK -> {
                // D-Pad 作为摇杆: HAT 轴值仅 -1/0/1
                val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).coerceIn(-1f, 1f)
                val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).coerceIn(-1f, 1f)
                Vector2(x, y)
            }
        }
    }

    /**
     * 从 MotionEvent 读取扳机的模拟值
     *
     * 不同设备使用不同的轴表示扳机:
     * - **左扳机 (LT)**: 优先 AXIS_LTRIGGER，回退到 AXIS_BRAKE
     * - **右扳机 (RT)**: 优先 AXIS_RTRIGGER，回退到 AXIS_GAS
     *
     * 返回值范围为 0.0（完全释放）~ 1.0（完全按下），已归一化。
     *
     * @param event MotionEvent
     * @param trigger 扳机类型
     * @param device 输入设备
     * @return 扳机值（0.0~1.0）
     */
    fun getTriggerValue(event: MotionEvent, trigger: ControllerTrigger, device: InputDevice): Float {
        val rawValue = when (trigger) {
            ControllerTrigger.LEFT_TRIGGER -> {
                var v = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                if (v <= 0f) v = event.getAxisValue(MotionEvent.AXIS_BRAKE)
                v
            }
            ControllerTrigger.RIGHT_TRIGGER -> {
                var v = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
                if (v <= 0f) v = event.getAxisValue(MotionEvent.AXIS_GAS)
                v
            }
        }
        return normalizeTrigger(rawValue, device, trigger)
    }

    /**
     * 读取摇杆轴值并归一化到 -1.0~1.0
     *
     * 处理步骤:
     * 1. 查询轴的运动范围（min/max/flat）
     * 2. 将原始值减去中点，得到相对位移
     * 3. 除以最大距离，归一化到 -1.0~1.0
     * 4. 应用 flat 死区（小于 flat 的值归零，防止漂移）
     *
     * @param event MotionEvent
     * @param device 输入设备
     * @param axis 轴常量（如 AXIS_X）
     * @return 归一化后的轴值（-1.0~1.0）
     */
    private fun getCenteredAxis(event: MotionEvent, device: InputDevice, axis: Int): Float {
        val range = device.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        val flat = range.flat  // 设备的死区值
        val mid = (range.min + range.max) / 2.0f  // 轴的中点
        val adjValue = value - mid  // 相对中点的位移
        val maxDistance = if (adjValue > 0) range.max - mid else mid - range.min
        val normalized = if (maxDistance == 0f) 0f else (adjValue / maxDistance).coerceIn(-1f, 1f)
        // 应用设备死区: 小于 flat 的输入归零
        return if (kotlin.math.abs(normalized) < flat) 0f else normalized
    }

    /**
     * 归一化扳机值到 0.0~1.0
     *
     * 不同设备的扳机轴范围不同:
     * - 部分设备: 0.0~1.0（直接使用）
     * - 部分设备: -1.0~1.0（需要 (value+1)/2 转换）
     *
     * 通过检测 range.min/max 判断设备类型并选择合适的转换方式。
     *
     * @param rawValue 原始轴值
     * @param device 输入设备
     * @param trigger 扳机类型（用于查询对应轴）
     * @return 归一化后的扳机值（0.0~1.0）
     */
    private fun normalizeTrigger(rawValue: Float, device: InputDevice, trigger: ControllerTrigger): Float {
        val axis = when (trigger) {
            ControllerTrigger.LEFT_TRIGGER -> MotionEvent.AXIS_LTRIGGER
            ControllerTrigger.RIGHT_TRIGGER -> MotionEvent.AXIS_RTRIGGER
        }
        val range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: return rawValue.coerceIn(0f, 1f)
        val min = range.min
        val max = range.max
        // 如果范围是 -1.0~1.0，需要转换为 0.0~1.0
        return if (min >= -1.01f && min <= -0.99f && max >= 0.99f && max <= 1.01f) {
            ((rawValue + 1f) / 2f).coerceIn(0f, 1f)
        } else {
            rawValue.coerceIn(0f, 1f)
        }
    }
}
