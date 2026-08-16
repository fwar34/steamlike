package com.steamlike.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ActionSetLayer 操作层测试
 *
 * 测试内容:
 * - 绑定覆盖 (overrideButtonBinding)
 * - 摇杆属性覆盖 (overrideStick)
 * - 扳机属性覆盖 (overrideTrigger)
 * - StickOverride/TriggerOverride 的 applyTo 方法
 * - 生命周期回调 (activate/deactivate)
 */
class ActionSetLayerTest {

    // ===== 绑定覆盖测试 =====

    @Test
    fun `覆盖按钮绑定`() {
        val layer = ActionSetLayer("Combat", "战斗模式")
        layer.overrideButtonBinding(ControllerButton.A, "Slot5")

        assertEquals("Slot5", layer.buttonBindingOverrides[ControllerButton.A])
    }

    @Test
    fun `覆盖多个按钮绑定`() {
        val layer = ActionSetLayer("Combat")
        layer.overrideButtonBinding(ControllerButton.A, "Slot5")
        layer.overrideButtonBinding(ControllerButton.B, "Slot6")
        layer.overrideButtonBinding(ControllerButton.X, "Slot7")
        layer.overrideButtonBinding(ControllerButton.Y, "Slot8")

        assertEquals(4, layer.buttonBindingOverrides.size)
        assertEquals("Slot5", layer.buttonBindingOverrides[ControllerButton.A])
        assertEquals("Slot6", layer.buttonBindingOverrides[ControllerButton.B])
        assertEquals("Slot7", layer.buttonBindingOverrides[ControllerButton.X])
        assertEquals("Slot8", layer.buttonBindingOverrides[ControllerButton.Y])
    }

    @Test
    fun `同一按钮多次覆盖只保留最后一次`() {
        val layer = ActionSetLayer("Combat")
        layer.overrideButtonBinding(ControllerButton.A, "Slot5")
        layer.overrideButtonBinding(ControllerButton.A, "Slot9")

        assertEquals(1, layer.buttonBindingOverrides.size)
        assertEquals("Slot9", layer.buttonBindingOverrides[ControllerButton.A])
    }

    // ===== 摇杆属性覆盖测试 =====

    @Test
    fun `覆盖摇杆死区`() {
        val layer = ActionSetLayer("Aim")
        layer.overrideStick("Look") {
            deadzone = 0.25f
        }

        val override = layer.stickOverrides["Look"]!!
        assertEquals(0.25f, override.deadzone!!, 0.001f)
        assertNull(override.responseCurve)
    }

    @Test
    fun `覆盖摇杆响应曲线`() {
        val layer = ActionSetLayer("Aim")
        layer.overrideStick("Look") {
            responseCurve = 0.5f
        }

        val override = layer.stickOverrides["Look"]!!
        assertNull(override.deadzone)
        assertEquals(0.5f, override.responseCurve!!, 0.001f)
    }

    @Test
    fun `同时覆盖摇杆死区和响应曲线`() {
        val layer = ActionSetLayer("Aim")
        layer.overrideStick("Look") {
            deadzone = 0.25f
            responseCurve = 0.5f
        }

        val override = layer.stickOverrides["Look"]!!
        assertEquals(0.25f, override.deadzone!!, 0.001f)
        assertEquals(0.5f, override.responseCurve!!, 0.001f)
    }

    @Test
    fun `多次调用overrideStick累积覆盖`() {
        val layer = ActionSetLayer("Aim")
        layer.overrideStick("Look") { deadzone = 0.25f }
        layer.overrideStick("Look") { responseCurve = 0.5f }

        val override = layer.stickOverrides["Look"]!!
        assertEquals(0.25f, override.deadzone!!, 0.001f)
        assertEquals(0.5f, override.responseCurve!!, 0.001f)
    }

    // ===== 扳机属性覆盖测试 =====

    @Test
    fun `覆盖扳机按压阈值`() {
        val layer = ActionSetLayer("Aim")
        layer.overrideTrigger("Cast") {
            pressThreshold = 0.6f
        }

        assertEquals(0.6f, layer.triggerOverrides["Cast"]!!.pressThreshold!!, 0.001f)
    }

    // ===== StickOverride.applyTo 测试 =====

    @Test
    fun `StickOverride applyTo 覆盖摇杆动作属性`() {
        val action = InputAction.StickPadGyroAction("Move").apply {
            deadzone = 0.15f
            responseCurve = 1.0f
        }
        val override = StickOverride(deadzone = 0.25f, responseCurve = 0.5f)

        override.applyTo(action)

        assertEquals(0.25f, action.deadzone, 0.001f)
        assertEquals(0.5f, action.responseCurve, 0.001f)
    }

    @Test
    fun `StickOverride null属性不覆盖原值`() {
        val action = InputAction.StickPadGyroAction("Move").apply {
            deadzone = 0.15f
            responseCurve = 1.0f
        }
        val override = StickOverride(deadzone = 0.3f, responseCurve = null)

        override.applyTo(action)

        assertEquals(0.3f, action.deadzone, 0.001f)
        assertEquals(1.0f, action.responseCurve, 0.001f)
    }

    // ===== TriggerOverride.applyTo 测试 =====

    @Test
    fun `TriggerOverride applyTo 覆盖扳机动作属性`() {
        val action = InputAction.AnalogTriggerAction("Cast").apply {
            pressThreshold = 0.5f
        }
        val override = TriggerOverride(pressThreshold = 0.3f)

        override.applyTo(action)

        assertEquals(0.3f, action.pressThreshold, 0.001f)
    }

    // ===== 生命周期回调测试 =====

    @Test
    fun `激活层触发onActivated并设置stackPosition`() {
        val layer = ActionSetLayer("Combat")
        var activated = false

        layer.onActivated = { activated = true }
        layer.activate(0)

        assertTrue(activated)
        assertEquals(0, layer.stackPosition)
    }

    @Test
    fun `停用层触发onDeactivated并重置stackPosition`() {
        val layer = ActionSetLayer("Combat")
        var deactivated = false

        layer.activate(2)
        layer.onDeactivated = { deactivated = true }
        layer.deactivate()

        assertTrue(deactivated)
        assertEquals(-1, layer.stackPosition)
    }

    @Test
    fun `未激活的层stackPosition为负1`() {
        val layer = ActionSetLayer("Combat")
        assertEquals(-1, layer.stackPosition)
    }

    // ===== 构造测试 =====

    @Test
    fun `层名称和显示名`() {
        val layer1 = ActionSetLayer("Combat")
        assertEquals("Combat", layer1.name)
        assertEquals("Combat", layer1.displayName)

        val layer2 = ActionSetLayer("Combat", "战斗模式")
        assertEquals("Combat", layer2.name)
        assertEquals("战斗模式", layer2.displayName)
    }

    @Test
    fun `新创建的层覆盖表为空`() {
        val layer = ActionSetLayer("Combat")
        assertTrue(layer.buttonBindingOverrides.isEmpty())
        assertTrue(layer.stickOverrides.isEmpty())
        assertTrue(layer.triggerOverrides.isEmpty())
    }
}
