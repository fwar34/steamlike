package com.steamlike.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ActionSet 动作集合容器测试
 *
 * 测试内容:
 * - 动作注册 (addButtonAction/addStickAction/addTriggerAction)
 * - 组合键绑定 (addChordBinding)
 * - 更新循环 (updateAll)
 *   - 按钮长按检测
 *   - 扳机阈值跨越检测
 *   - 摇杆死区+响应曲线处理
 *   - 摇杆8方向计算
 * - 状态重置 (resetState)
 */
class ActionSetTest {

    // ===== 动作注册测试 =====

    @Test
    fun `注册按钮动作`() {
        val actionSet = ActionSet("test")
        val action = actionSet.addButtonAction("Jump") {
            onPressed = { /* jump */ }
        }
        assertNotNull(actionSet.buttonActions["Jump"])
        assertEquals("Jump", action.name)
        assertEquals(InputActionType.BUTTON, action.type)
    }

    @Test
    fun `注册扳机动作`() {
        val actionSet = ActionSet("test")
        val action = actionSet.addTriggerAction("Cast") {
            pressThreshold = 0.3f
        }
        assertNotNull(actionSet.triggerActions["Cast"])
        assertEquals(0.3f, action.pressThreshold, 0.001f)
    }

    @Test
    fun `注册摇杆动作`() {
        val actionSet = ActionSet("test")
        val action = actionSet.addStickAction("Move") {
            deadzone = 0.2f
            responseCurve = 1.5f
        }
        assertNotNull(actionSet.stickActions["Move"])
        assertEquals(0.2f, action.deadzone, 0.001f)
        assertEquals(1.5f, action.responseCurve, 0.001f)
    }

    @Test
    fun `重复注册同名动作会覆盖`() {
        val actionSet = ActionSet("test")
        actionSet.addButtonAction("Jump") { onPressed = { } }
        actionSet.addButtonAction("Jump") { onPressed = { } }
        assertEquals(1, actionSet.buttonActions.size)
    }

    // ===== 组合键绑定测试 =====

    @Test
    fun `添加组合键绑定`() {
        val actionSet = ActionSet("test")
        val cb = actionSet.addChordBinding(
            ControllerButton.A, "Slot5",
            setOf(ControllerButton.RIGHT_SHOULDER)
        )
        assertEquals(1, actionSet.chordBindings.size)
        assertEquals(ControllerButton.A, cb.button)
        assertEquals("Slot5", cb.actionName)
        assertEquals(1, cb.chordSize)
    }

    @Test
    fun `添加多个组合键绑定`() {
        val actionSet = ActionSet("test")
        actionSet.addChordBinding(ControllerButton.A, "Jump")
        actionSet.addChordBinding(ControllerButton.A, "Slot5", setOf(ControllerButton.RIGHT_SHOULDER))
        actionSet.addChordBinding(ControllerButton.A, "Potion",
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK))
        assertEquals(3, actionSet.chordBindings.size)
    }

    // ===== updateAll - 按钮长按检测 =====

    @Test
    fun `按钮按下后heldTimeMs随帧累加`() {
        val actionSet = ActionSet("test")
        var updateCallCount = 0
        var lastHeld = false
        var lastHeldTime = 0L

        actionSet.addButtonAction("Jump") {
            onUpdate = { held, timeMs ->
                updateCallCount++
                lastHeld = held
                lastHeldTime = timeMs
            }
        }

        // 模拟按钮按下
        actionSet.buttonActions["Jump"]!!.isPressed = true

        // 第一帧: 16ms
        actionSet.updateAll(16)
        assertTrue(lastHeld)
        assertEquals(16, lastHeldTime)

        // 第二帧: 再加16ms
        actionSet.updateAll(16)
        assertEquals(32, lastHeldTime)

        // 第三帧: 再加16ms
        actionSet.updateAll(16)
        assertEquals(48, lastHeldTime)
        assertEquals(3, updateCallCount)
    }

    @Test
    fun `按钮释放后heldTimeMs不再累加`() {
        val actionSet = ActionSet("test")
        var lastHeldTime = -1L

        actionSet.addButtonAction("Jump") {
            onUpdate = { _, timeMs -> lastHeldTime = timeMs }
        }

        actionSet.buttonActions["Jump"]!!.isPressed = true
        actionSet.updateAll(16)
        actionSet.updateAll(16)
        assertEquals(32, lastHeldTime)

        // 释放 (模拟 dispatchButton 的行为: isPressed=false + heldTimeMs=0)
        actionSet.buttonActions["Jump"]!!.isPressed = false
        actionSet.buttonActions["Jump"]!!.heldTimeMs = 0
        actionSet.updateAll(16)
        assertEquals(0, lastHeldTime)
    }

    // ===== updateAll - 扳机阈值检测 =====

    @Test
    fun `扳机超过阈值触发onPressed`() {
        val actionSet = ActionSet("test")
        var pressed = false
        var released = false

        actionSet.addTriggerAction("Cast") {
            pressThreshold = 0.5f
            onPressed = { pressed = true }
            onReleased = { released = true }
        }

        val action = actionSet.triggerActions["Cast"]!!

        // 值从0→0.3（未超过阈值）
        action.currentValue = 0.3f
        actionSet.updateAll(16)
        assertFalse(pressed)

        // 值从0.3→0.6（超过阈值）
        action.currentValue = 0.6f
        actionSet.updateAll(16)
        assertTrue(pressed)
        assertFalse(released)
    }

    @Test
    fun `扳机低于阈值触发onReleased`() {
        val actionSet = ActionSet("test")
        var pressed = false
        var released = false

        actionSet.addTriggerAction("Cast") {
            pressThreshold = 0.5f
            onPressed = { pressed = true }
            onReleased = { released = true }
        }

        val action = actionSet.triggerActions["Cast"]!!

        // 先按下
        action.currentValue = 0.8f
        actionSet.updateAll(16)
        assertTrue(pressed)

        // 再释放
        action.currentValue = 0.2f
        actionSet.updateAll(16)
        assertTrue(released)
    }

    @Test
    fun `扳机值在阈值附近波动不重复触发`() {
        val actionSet = ActionSet("test")
        var pressCount = 0
        var releaseCount = 0

        actionSet.addTriggerAction("Cast") {
            pressThreshold = 0.5f
            onPressed = { pressCount++ }
            onReleased = { releaseCount++ }
        }

        val action = actionSet.triggerActions["Cast"]!!

        // 按下
        action.currentValue = 0.6f
        actionSet.updateAll(16)
        assertEquals(1, pressCount)

        // 再次超过阈值（不重复触发）
        action.currentValue = 0.7f
        actionSet.updateAll(16)
        assertEquals(1, pressCount)

        // 释放
        action.currentValue = 0.4f
        actionSet.updateAll(16)
        assertEquals(1, releaseCount)

        // 再次低于阈值（不重复触发）
        action.currentValue = 0.3f
        actionSet.updateAll(16)
        assertEquals(1, releaseCount)
    }

    // ===== updateAll - 摇杆死区+响应曲线 =====

    @Test
    fun `摇杆死区内输入归零`() {
        val actionSet = ActionSet("test")
        var lastValue: Vector2 = Vector2.ZERO

        actionSet.addStickAction("Move") {
            deadzone = 0.2f
            responseCurve = 1.0f
            onValueChanged = { v -> lastValue = v }
        }

        val action = actionSet.stickActions["Move"]!!
        action.rawValue = Vector2(0.1f, 0.05f)  // magnitude ≈ 0.112 < 0.2
        actionSet.updateAll(16)

        assertEquals(0f, lastValue.x, 0.001f)
        assertEquals(0f, lastValue.y, 0.001f)
    }

    @Test
    fun `摇杆死区外输入应用响应曲线`() {
        val actionSet = ActionSet("test")
        var lastValue: Vector2 = Vector2.ZERO

        actionSet.addStickAction("Move") {
            deadzone = 0.15f
            responseCurve = 1.0f  // 线性
            onValueChanged = { v -> lastValue = v }
        }

        val action = actionSet.stickActions["Move"]!!
        // 向右推满: magnitude=1, 经过死区后 scale=(1-0.15)/(1-0.15)=1.0
        action.rawValue = Vector2(1f, 0f)
        actionSet.updateAll(16)

        assertEquals(1f, lastValue.x, 0.01f)
        assertEquals(0f, lastValue.y, 0.01f)
    }

    @Test
    fun `摇杆响应曲线大于1时前半段更慢`() {
        val actionSet = ActionSet("test")
        var lastValue: Vector2 = Vector2.ZERO

        actionSet.addStickAction("Move") {
            deadzone = 0.0f  // 无死区，便于验证纯曲线效果
            responseCurve = 2.0f  // 平方曲线
            onValueChanged = { v -> lastValue = v }
        }

        val action = actionSet.stickActions["Move"]!!
        // magnitude=0.5, 曲线=2.0 → 0.5^2 = 0.25
        action.rawValue = Vector2(0.5f, 0f)
        actionSet.updateAll(16)

        assertEquals(0.25f, lastValue.x, 0.01f)
    }

    @Test
    fun `摇杆响应曲线小于1时前半段更快`() {
        val actionSet = ActionSet("test")
        var lastValue: Vector2 = Vector2.ZERO

        actionSet.addStickAction("Move") {
            deadzone = 0.0f
            responseCurve = 0.5f  // 平方根曲线
            onValueChanged = { v -> lastValue = v }
        }

        val action = actionSet.stickActions["Move"]!!
        // magnitude=0.25, 曲线=0.5 → 0.25^0.5 = 0.5
        action.rawValue = Vector2(0.25f, 0f)
        actionSet.updateAll(16)

        assertEquals(0.5f, lastValue.x, 0.01f)
    }

    // ===== updateAll - 摇杆8方向 =====

    @Test
    fun `摇杆向右推触发RIGHT方向`() {
        val actionSet = ActionSet("test")
        var lastDir: InputAction.StickPadGyroAction.StickDirection? = null

        actionSet.addStickAction("Move") {
            deadzone = 0.1f
            onDirectionChanged = { dir -> lastDir = dir }
        }

        actionSet.stickActions["Move"]!!.rawValue = Vector2(1f, 0f)
        actionSet.updateAll(16)

        assertEquals(InputAction.StickPadGyroAction.StickDirection.RIGHT, lastDir)
    }

    @Test
    fun `摇杆向下推触发DOWN方向`() {
        val actionSet = ActionSet("test")
        var lastDir: InputAction.StickPadGyroAction.StickDirection? = null

        actionSet.addStickAction("Move") {
            deadzone = 0.1f
            onDirectionChanged = { dir -> lastDir = dir }
        }

        actionSet.stickActions["Move"]!!.rawValue = Vector2(0f, 1f)  // Y轴下为正
        actionSet.updateAll(16)

        assertEquals(InputAction.StickPadGyroAction.StickDirection.DOWN, lastDir)
    }

    @Test
    fun `摇杆向右上推触发UP_RIGHT方向`() {
        val actionSet = ActionSet("test")
        var lastDir: InputAction.StickPadGyroAction.StickDirection? = null

        actionSet.addStickAction("Move") {
            deadzone = 0.1f
            onDirectionChanged = { dir -> lastDir = dir }
        }

        actionSet.stickActions["Move"]!!.rawValue = Vector2(1f, -1f)  // 右上
        actionSet.updateAll(16)

        assertEquals(InputAction.StickPadGyroAction.StickDirection.UP_RIGHT, lastDir)
    }

    @Test
    fun `摇杆归零触发CENTER方向`() {
        val actionSet = ActionSet("test")
        var lastDir: InputAction.StickPadGyroAction.StickDirection? = null

        actionSet.addStickAction("Move") {
            deadzone = 0.1f
            onDirectionChanged = { dir -> lastDir = dir }
        }

        val action = actionSet.stickActions["Move"]!!
        // 先推右
        action.rawValue = Vector2(1f, 0f)
        actionSet.updateAll(16)
        assertEquals(InputAction.StickPadGyroAction.StickDirection.RIGHT, lastDir)

        // 归零
        action.rawValue = Vector2.ZERO
        actionSet.updateAll(16)
        assertEquals(InputAction.StickPadGyroAction.StickDirection.CENTER, lastDir)
    }

    @Test
    fun `摇杆方向不变时不重复触发`() {
        val actionSet = ActionSet("test")
        var dirCallCount = 0

        actionSet.addStickAction("Move") {
            deadzone = 0.1f
            onDirectionChanged = { dir -> dirCallCount++ }
        }

        val action = actionSet.stickActions["Move"]!!
        // 连续推右，方向不变
        action.rawValue = Vector2(0.5f, 0f)
        actionSet.updateAll(16)
        action.rawValue = Vector2(0.8f, 0f)
        actionSet.updateAll(16)
        action.rawValue = Vector2(1.0f, 0f)
        actionSet.updateAll(16)

        assertEquals(1, dirCallCount)
    }

    // ===== resetState 测试 =====

    @Test
    fun `resetState清零按钮状态`() {
        val actionSet = ActionSet("test")
        actionSet.addButtonAction("Jump") {}
        actionSet.buttonActions["Jump"]!!.isPressed = true
        actionSet.buttonActions["Jump"]!!.heldTimeMs = 500

        actionSet.resetState()

        assertFalse(actionSet.buttonActions["Jump"]!!.isPressed)
        assertEquals(0, actionSet.buttonActions["Jump"]!!.heldTimeMs)
    }

    @Test
    fun `resetState清零扳机状态`() {
        val actionSet = ActionSet("test")
        actionSet.addTriggerAction("Cast") {}
        actionSet.triggerActions["Cast"]!!.currentValue = 0.8f
        actionSet.triggerActions["Cast"]!!.isPressed = true

        actionSet.resetState()

        assertEquals(0f, actionSet.triggerActions["Cast"]!!.currentValue, 0.001f)
        assertFalse(actionSet.triggerActions["Cast"]!!.isPressed)
    }

    @Test
    fun `resetState清零摇杆状态并触发释放回调`() {
        val actionSet = ActionSet("test")
        var releasedValue: Vector2? = null

        actionSet.addStickAction("Move") {
            onValueChanged = { v -> releasedValue = v }
        }
        actionSet.stickActions["Move"]!!.currentValue = Vector2(0.5f, 0.5f)
        actionSet.stickActions["Move"]!!.rawValue = Vector2(0.5f, 0.5f)

        actionSet.resetState()

        assertEquals(Vector2.ZERO, actionSet.stickActions["Move"]!!.currentValue)
        assertEquals(Vector2.ZERO, actionSet.stickActions["Move"]!!.rawValue)
        assertEquals(Vector2.ZERO, releasedValue)
    }
}
