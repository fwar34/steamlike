package com.steamlike.controller.core

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 已连接的手柄设备
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
        fun fromInputDevice(device: InputDevice): ControllerDevice? {
            val sources = device.sources
            val isGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            val isJoystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (!isGamepad && !isJoystick) return null

            val hasLeftStick = device.getMotionRange(MotionEvent.AXIS_X) != null &&
                    device.getMotionRange(MotionEvent.AXIS_Y) != null
            val hasRightStick = device.getMotionRange(MotionEvent.AXIS_Z) != null ||
                    device.getMotionRange(MotionEvent.AXIS_RX) != null
            val hasAnalogTriggers = device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null ||
                    device.getMotionRange(MotionEvent.AXIS_BRAKE) != null
            val hasDpad = sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

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
 * 手柄输入映射器: 将 Android 原生 KeyEvent / MotionEvent 映射到统一 ControllerButton
 */
object ControllerInputMapper {

    private val keyCodeMap: Map<Int, ControllerButton> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to ControllerButton.A,
        KeyEvent.KEYCODE_BUTTON_B to ControllerButton.B,
        KeyEvent.KEYCODE_BUTTON_X to ControllerButton.X,
        KeyEvent.KEYCODE_BUTTON_Y to ControllerButton.Y,
        KeyEvent.KEYCODE_BUTTON_L1 to ControllerButton.LEFT_SHOULDER,
        KeyEvent.KEYCODE_BUTTON_R1 to ControllerButton.RIGHT_SHOULDER,
        KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerButton.LEFT_STICK_CLICK,
        KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerButton.RIGHT_STICK_CLICK,
        KeyEvent.KEYCODE_BUTTON_L2 to ControllerButton.LEFT_TRIGGER_CLICK,
        KeyEvent.KEYCODE_BUTTON_R2 to ControllerButton.RIGHT_TRIGGER_CLICK,
        KeyEvent.KEYCODE_BUTTON_START to ControllerButton.MENU,
        KeyEvent.KEYCODE_BUTTON_SELECT to ControllerButton.OPTIONS,
        KeyEvent.KEYCODE_BUTTON_MODE to ControllerButton.GUIDE,
        KeyEvent.KEYCODE_DPAD_UP to ControllerButton.DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to ControllerButton.DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to ControllerButton.DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to ControllerButton.DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_C to ControllerButton.TOUCHPAD_CLICK
    )

    // PS手柄 X/O 与 Xbox A/B 位置互换
    private val psKeyCodeOverrides: Map<Int, ControllerButton> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to ControllerButton.X,
        KeyEvent.KEYCODE_BUTTON_B to ControllerButton.B,
        KeyEvent.KEYCODE_BUTTON_X to ControllerButton.A,
        KeyEvent.KEYCODE_BUTTON_Y to ControllerButton.Y
    )

    fun mapKeyCode(keyCode: Int, controllerType: ControllerType): ControllerButton? {
        if (controllerType == ControllerType.PS3 || controllerType == ControllerType.PS4 ||
            controllerType == ControllerType.PS5_DUALSENSE
        ) {
            psKeyCodeOverrides[keyCode]?.let { return it }
        }
        return keyCodeMap[keyCode]
    }

    fun getStickValue(event: MotionEvent, stick: ControllerStick, device: InputDevice): Vector2 {
        return when (stick) {
            ControllerStick.LEFT_STICK -> {
                val x = getCenteredAxis(event, device, MotionEvent.AXIS_X)
                val y = getCenteredAxis(event, device, MotionEvent.AXIS_Y)
                Vector2(x, y)
            }
            ControllerStick.RIGHT_STICK -> {
                var x = getCenteredAxis(event, device, MotionEvent.AXIS_Z)
                if (x == 0f) x = getCenteredAxis(event, device, MotionEvent.AXIS_RX)
                var y = getCenteredAxis(event, device, MotionEvent.AXIS_RZ)
                if (y == 0f) y = getCenteredAxis(event, device, MotionEvent.AXIS_RY)
                Vector2(x, y)
            }
            ControllerStick.DPAD_AS_STICK -> {
                val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).coerceIn(-1f, 1f)
                val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).coerceIn(-1f, 1f)
                Vector2(x, y)
            }
        }
    }

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

    private fun getCenteredAxis(event: MotionEvent, device: InputDevice, axis: Int): Float {
        val range = device.getMotionRange(axis, event.source) ?: return 0f
        val value = event.getAxisValue(axis)
        val flat = range.flat
        val mid = (range.min + range.max) / 2.0f
        val adjValue = value - mid
        val maxDistance = if (adjValue > 0) range.max - mid else mid - range.min
        val normalized = if (maxDistance == 0f) 0f else (adjValue / maxDistance).coerceIn(-1f, 1f)
        return if (kotlin.math.abs(normalized) < flat) 0f else normalized
    }

    private fun normalizeTrigger(rawValue: Float, device: InputDevice, trigger: ControllerTrigger): Float {
        val axis = when (trigger) {
            ControllerTrigger.LEFT_TRIGGER -> MotionEvent.AXIS_LTRIGGER
            ControllerTrigger.RIGHT_TRIGGER -> MotionEvent.AXIS_RTRIGGER
        }
        val range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: return rawValue.coerceIn(0f, 1f)
        val min = range.min
        val max = range.max
        return if (min >= -1.01f && min <= -0.99f && max >= 0.99f && max <= 1.01f) {
            ((rawValue + 1f) / 2f).coerceIn(0f, 1f)
        } else {
            rawValue.coerceIn(0f, 1f)
        }
    }
}
