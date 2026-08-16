package com.steamlike.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ControllerTypes 枚举与数据结构测试
 *
 * 测试内容:
 * - ControllerType.fromVendorProduct (手柄类型识别)
 * - ControllerState 状态查询
 * - ControllerButton 枚举完整性
 */
class ControllerTypesTest {

    // ===== ControllerType 识别测试 =====

    @Test
    fun `识别Xbox 360手柄`() {
        val type = ControllerType.fromVendorProduct(0x045E, 0x028E)
        assertEquals(ControllerType.XBOX_360, type)
    }

    @Test
    fun `识别Xbox One手柄`() {
        val type = ControllerType.fromVendorProduct(0x045E, 0x02DD)
        assertEquals(ControllerType.XBOX_ONE, type)
    }

    @Test
    fun `识别PS4手柄`() {
        val type = ControllerType.fromVendorProduct(0x054C, 0x05C4)
        assertEquals(ControllerType.PS4, type)
    }

    @Test
    fun `识别PS5手柄`() {
        val type = ControllerType.fromVendorProduct(0x054C, 0x0CE6)
        assertEquals(ControllerType.PS5_DUALSENSE, type)
    }

    @Test
    fun `识别Switch Pro手柄`() {
        val type = ControllerType.fromVendorProduct(0x057E, 0x2009)
        assertEquals(ControllerType.SWITCH_PRO, type)
    }

    @Test
    fun `识别Steam Deck`() {
        val type = ControllerType.fromVendorProduct(0x28DE, 0x1205)
        assertEquals(ControllerType.STEAM_DECK, type)
    }

    @Test
    fun `未知VID_PID返回GENERIC`() {
        val type = ControllerType.fromVendorProduct(0x1234, 0x5678)
        assertEquals(ControllerType.GENERIC, type)
    }

    @Test
    fun `同VID不同PID返回正确类型`() {
        // Xbox 360 和 Xbox One 都是 0x045E，但 PID 不同
        assertEquals(ControllerType.XBOX_360, ControllerType.fromVendorProduct(0x045E, 0x028E))
        assertEquals(ControllerType.XBOX_ONE, ControllerType.fromVendorProduct(0x045E, 0x02DD))
        assertEquals(ControllerType.XBOX_ELITE, ControllerType.fromVendorProduct(0x045E, 0x0B00))
    }

    // ===== ControllerState 测试 =====

    @Test
    fun `ControllerState查询按钮状态`() {
        val state = ControllerState(
            deviceId = 1,
            timestamp = 1000,
            buttons = mapOf(
                ControllerButton.A to true,
                ControllerButton.B to false,
                ControllerButton.X to true
            )
        )
        assertTrue(state.isButtonPressed(ControllerButton.A))
        assertFalse(state.isButtonPressed(ControllerButton.B))
        assertTrue(state.isButtonPressed(ControllerButton.X))
        assertFalse(state.isButtonPressed(ControllerButton.Y))  // 未设置 = false
    }

    @Test
    fun `ControllerState查询摇杆位置`() {
        val state = ControllerState(
            deviceId = 1,
            timestamp = 1000,
            sticks = mapOf(
                ControllerStick.LEFT_STICK to Vector2(0.5f, 0.3f)
            )
        )
        val leftStick = state.getStick(ControllerStick.LEFT_STICK)
        assertEquals(0.5f, leftStick.x, 0.001f)
        assertEquals(0.3f, leftStick.y, 0.001f)

        // 未设置的摇杆返回零向量
        val rightStick = state.getStick(ControllerStick.RIGHT_STICK)
        assertEquals(Vector2.ZERO, rightStick)
    }

    @Test
    fun `ControllerState查询扳机值`() {
        val state = ControllerState(
            deviceId = 1,
            timestamp = 1000,
            triggers = mapOf(
                ControllerTrigger.LEFT_TRIGGER to 0.7f
            )
        )
        assertEquals(0.7f, state.getTrigger(ControllerTrigger.LEFT_TRIGGER), 0.001f)
        assertEquals(0f, state.getTrigger(ControllerTrigger.RIGHT_TRIGGER), 0.001f)
    }

    @Test
    fun `空ControllerState所有查询返回默认值`() {
        val state = ControllerState(deviceId = 1, timestamp = 0)
        assertFalse(state.isButtonPressed(ControllerButton.A))
        assertEquals(Vector2.ZERO, state.getStick(ControllerStick.LEFT_STICK))
        assertEquals(0f, state.getTrigger(ControllerTrigger.LEFT_TRIGGER), 0.001f)
    }

    // ===== 枚举完整性测试 =====

    @Test
    fun `ControllerButton包含所有标准按键`() {
        val buttons = ControllerButton.values()
        // 面部按钮
        assertTrue(buttons.contains(ControllerButton.A))
        assertTrue(buttons.contains(ControllerButton.B))
        assertTrue(buttons.contains(ControllerButton.X))
        assertTrue(buttons.contains(ControllerButton.Y))
        // 肩键
        assertTrue(buttons.contains(ControllerButton.LEFT_SHOULDER))
        assertTrue(buttons.contains(ControllerButton.RIGHT_SHOULDER))
        // 扳机点击
        assertTrue(buttons.contains(ControllerButton.LEFT_TRIGGER_CLICK))
        assertTrue(buttons.contains(ControllerButton.RIGHT_TRIGGER_CLICK))
        // 摇杆点击
        assertTrue(buttons.contains(ControllerButton.LEFT_STICK_CLICK))
        assertTrue(buttons.contains(ControllerButton.RIGHT_STICK_CLICK))
        // 菜单键
        assertTrue(buttons.contains(ControllerButton.MENU))
        assertTrue(buttons.contains(ControllerButton.OPTIONS))
        assertTrue(buttons.contains(ControllerButton.GUIDE))
        // 方向键
        assertTrue(buttons.contains(ControllerButton.DPAD_UP))
        assertTrue(buttons.contains(ControllerButton.DPAD_DOWN))
        assertTrue(buttons.contains(ControllerButton.DPAD_LEFT))
        assertTrue(buttons.contains(ControllerButton.DPAD_RIGHT))
    }

    @Test
    fun `ControllerStick包含左摇杆右摇杆和D-Pad`() {
        val sticks = ControllerStick.values()
        assertEquals(3, sticks.size)
        assertTrue(sticks.contains(ControllerStick.LEFT_STICK))
        assertTrue(sticks.contains(ControllerStick.RIGHT_STICK))
        assertTrue(sticks.contains(ControllerStick.DPAD_AS_STICK))
    }

    @Test
    fun `ControllerTrigger包含左右扳机`() {
        val triggers = ControllerTrigger.values()
        assertEquals(2, triggers.size)
        assertTrue(triggers.contains(ControllerTrigger.LEFT_TRIGGER))
        assertTrue(triggers.contains(ControllerTrigger.RIGHT_TRIGGER))
    }

    @Test
    fun `InputActionType有三种类型`() {
        val types = InputActionType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(InputActionType.BUTTON))
        assertTrue(types.contains(InputActionType.ANALOG_TRIGGER))
        assertTrue(types.contains(InputActionType.STICK_PAD_GYRO))
    }
}
