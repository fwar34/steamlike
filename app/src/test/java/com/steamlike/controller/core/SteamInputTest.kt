package com.steamlike.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SteamInput 绑定查找逻辑测试
 *
 * 由于 SteamInput 构造需要 Android Context (InputManager)，
 * 本测试通过直接操作 ActionSet + ActionSetLayer + ChordBinding 来验证
 * getEffectiveButtonBinding 的核心算法逻辑。
 *
 * 测试内容:
 * - 组合键匹配 (chord binding lookup)
 * - 操作层覆盖 (layer override lookup)
 * - 多层栈优先级 (stack priority)
 * - 绑定回退 (fallback to common layer)
 * - heldButtons 维护与组合键交互
 */
class SteamInputTest {

    /**
     * 模拟 SteamInput.getEffectiveButtonBinding 的核心逻辑
     *
     * 查找顺序:
     * 1. 组合键绑定 (chordSize 最大的匹配优先)
     * 2. 操作层覆盖 (栈顶到栈底)
     * 3. 公共层默认绑定
     */
    private fun lookupBinding(
        button: ControllerButton,
        heldButtons: Set<ControllerButton>,
        commonLayer: ActionSet,
        activeLayerStack: List<ActionSetLayer>
    ): String? {
        // 1. 检查组合键绑定
        var bestChord: ChordBinding? = null
        for (cb in commonLayer.chordBindings) {
            if (cb.button != button) continue
            if (cb.chord.isEmpty()) continue
            if (!cb.matches(heldButtons)) continue
            if (bestChord == null || cb.chordSize > bestChord.chordSize) {
                bestChord = cb
            }
        }
        if (bestChord != null) return bestChord.actionName

        // 2. 从栈顶到栈底查找层覆盖
        for (i in activeLayerStack.indices.reversed()) {
            activeLayerStack[i].buttonBindingOverrides[button]?.let { return it }
        }
        // 3. 回退到公共层默认绑定
        return commonLayer.buttonBindings[button]
    }

    /**
     * 模拟按钮事件分发（更新 heldButtons + 查找绑定 + 触发回调）
     */
    private fun dispatchButton(
        button: ControllerButton,
        isPressed: Boolean,
        heldButtons: MutableSet<ControllerButton>,
        commonLayer: ActionSet,
        activeLayerStack: List<ActionSetLayer>
    ) {
        if (isPressed) heldButtons.add(button) else heldButtons.remove(button)
        val actionName = lookupBinding(button, heldButtons, commonLayer, activeLayerStack) ?: return
        val action = commonLayer.buttonActions[actionName] ?: return
        if (isPressed) {
            if (!action.isPressed) {
                action.isPressed = true
                action.heldTimeMs = 0
                action.onPressed?.invoke()
            }
        } else {
            if (action.isPressed) {
                action.isPressed = false
                action.onReleased?.invoke()
                action.heldTimeMs = 0
            }
        }
    }

    // ===== 基础绑定查找测试 =====

    @Test
    fun `无修饰键时使用公共层默认绑定`() {
        val commonLayer = ActionSet("__common__")
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"
        val heldButtons = emptySet<ControllerButton>()

        val result = lookupBinding(ControllerButton.A, heldButtons, commonLayer, emptyList())
        assertEquals("Jump", result)
    }

    @Test
    fun `未绑定的按钮返回null`() {
        val commonLayer = ActionSet("__common__")
        val result = lookupBinding(ControllerButton.A, emptySet(), commonLayer, emptyList())
        assertEquals(null, result)
    }

    // ===== 组合键匹配测试 =====

    @Test
    fun `组合键在修饰键按住时触发`() {
        val commonLayer = ActionSet("__common__")
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"
        commonLayer.addChordBinding(ControllerButton.A, "TargetEnemy",
            setOf(ControllerButton.RIGHT_SHOULDER))

        // RB 按住时
        var result = lookupBinding(
            ControllerButton.A,
            setOf(ControllerButton.RIGHT_SHOULDER),
            commonLayer, emptyList()
        )
        assertEquals("TargetEnemy", result)

        // 无修饰键时
        result = lookupBinding(ControllerButton.A, emptySet(), commonLayer, emptyList())
        assertEquals("Jump", result)
    }

    @Test
    fun `多键chord优先于单键chord`() {
        val commonLayer = ActionSet("__common__")
        commonLayer.buttonBindings[ControllerButton.A] = "Default"
        commonLayer.addChordBinding(ControllerButton.A, "Slot5",
            setOf(ControllerButton.RIGHT_SHOULDER))
        commonLayer.addChordBinding(ControllerButton.A, "Potion",
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK))

        // 只按住 RB → Slot5 (chordSize=1)
        var result = lookupBinding(
            ControllerButton.A,
            setOf(ControllerButton.RIGHT_SHOULDER),
            commonLayer, emptyList()
        )
        assertEquals("Slot5", result)

        // 按住 RB + LT → Potion (chordSize=2, 优先)
        result = lookupBinding(
            ControllerButton.A,
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK),
            commonLayer, emptyList()
        )
        assertEquals("Potion", result)
    }

    @Test
    fun `chord部分匹配时不触发`() {
        val commonLayer = ActionSet("__common__")
        commonLayer.buttonBindings[ControllerButton.A] = "Default"
        commonLayer.addChordBinding(ControllerButton.A, "Potion",
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK))

        // 只按住 RB (缺少 LT) → 回退到默认
        val result = lookupBinding(
            ControllerButton.A,
            setOf(ControllerButton.RIGHT_SHOULDER),
            commonLayer, emptyList()
        )
        assertEquals("Default", result)
    }

    // ===== 操作层覆盖测试 =====

    @Test
    fun `操作层覆盖按钮绑定`() {
        val commonLayer = ActionSet("__common__")
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"

        val combat = ActionSetLayer("Combat")
        combat.overrideButtonBinding(ControllerButton.A, "Slot5")

        // 未激活层 → 公共层绑定
        var result = lookupBinding(ControllerButton.A, emptySet(), commonLayer, emptyList())
        assertEquals("Jump", result)

        // 激活层 → 层覆盖
        result = lookupBinding(ControllerButton.A, emptySet(), commonLayer, listOf(combat))
        assertEquals("Slot5", result)
    }

    @Test
    fun `栈顶层覆盖优先级最高`() {
        val commonLayer = ActionSet("__common__")
        commonLayer.buttonBindings[ControllerButton.A] = "Default"

        val combat = ActionSetLayer("Combat")
        combat.overrideButtonBinding(ControllerButton.A, "Slot5")

        val raid = ActionSetLayer("Raid")
        raid.overrideButtonBinding(ControllerButton.A, "Slot9")

        // 栈: [Combat, Raid] → Raid 在栈顶
        val stack = listOf(combat, raid)
        val result = lookupBinding(ControllerButton.A, emptySet(), commonLayer, stack)
        assertEquals("Slot9", result)
    }

    @Test
    fun `层未覆盖的按钮回退到公共层`() {
        val commonLayer = ActionSet("__common__")
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"
        commonLayer.buttonBindings[ControllerButton.B] = "Interact"

        val combat = ActionSetLayer("Combat")
        combat.overrideButtonBinding(ControllerButton.A, "Slot5")
        // Combat 层未覆盖 B

        val stack = listOf(combat)
        // A → 被层覆盖
        assertEquals("Slot5", lookupBinding(ControllerButton.A, emptySet(), commonLayer, stack))
        // B → 回退到公共层
        assertEquals("Interact", lookupBinding(ControllerButton.B, emptySet(), commonLayer, stack))
    }

    // ===== 完整事件分发测试 =====

    @Test
    fun `按钮按下触发onPressed`() {
        val commonLayer = ActionSet("__common__")
        var pressed = false
        commonLayer.addButtonAction("Jump") { onPressed = { pressed = true } }
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"

        val heldButtons = mutableSetOf<ControllerButton>()
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())

        assertTrue(pressed)
        assertTrue(heldButtons.contains(ControllerButton.A))
    }

    @Test
    fun `按钮释放触发onReleased`() {
        val commonLayer = ActionSet("__common__")
        var released = false
        commonLayer.addButtonAction("Jump") { onReleased = { released = true } }
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"

        val heldButtons = mutableSetOf<ControllerButton>()
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())
        dispatchButton(ControllerButton.A, false, heldButtons, commonLayer, emptyList())

        assertTrue(released)
        assertFalse(heldButtons.contains(ControllerButton.A))
    }

    @Test
    fun `防重复按下不重复触发`() {
        val commonLayer = ActionSet("__common__")
        var pressCount = 0
        commonLayer.addButtonAction("Jump") { onPressed = { pressCount++ } }
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"

        val heldButtons = mutableSetOf<ControllerButton>()
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())

        assertEquals(1, pressCount)
    }

    @Test
    fun `组合键完整流程 - 按住修饰键再按按钮`() {
        val commonLayer = ActionSet("__common__")
        var jumpPressed = false
        var targetPressed = false

        commonLayer.addButtonAction("Jump") { onPressed = { jumpPressed = true } }
        commonLayer.addButtonAction("TargetEnemy") { onPressed = { targetPressed = true } }
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"
        commonLayer.addChordBinding(ControllerButton.A, "TargetEnemy",
            setOf(ControllerButton.RIGHT_SHOULDER))

        val heldButtons = mutableSetOf<ControllerButton>()

        // 先按住 RB
        dispatchButton(ControllerButton.RIGHT_SHOULDER, true, heldButtons, commonLayer, emptyList())
        // 再按 A → 应该触发 TargetEnemy (组合键匹配)
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())

        assertFalse(jumpPressed)
        assertTrue(targetPressed)
    }

    @Test
    fun `组合键完整流程 - 释放修饰键后回退到默认`() {
        val commonLayer = ActionSet("__common__")
        var jumpPressed = false
        var targetPressed = false

        commonLayer.addButtonAction("Jump") { onPressed = { jumpPressed = true } }
        commonLayer.addButtonAction("TargetEnemy") { onPressed = { targetPressed = true } }
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"
        commonLayer.addChordBinding(ControllerButton.A, "TargetEnemy",
            setOf(ControllerButton.RIGHT_SHOULDER))

        val heldButtons = mutableSetOf<ControllerButton>()

        // RB + A → TargetEnemy
        dispatchButton(ControllerButton.RIGHT_SHOULDER, true, heldButtons, commonLayer, emptyList())
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())
        assertTrue(targetPressed)

        // 释放 A 和 RB
        dispatchButton(ControllerButton.A, false, heldButtons, commonLayer, emptyList())
        dispatchButton(ControllerButton.RIGHT_SHOULDER, false, heldButtons, commonLayer, emptyList())

        // 重置
        targetPressed = false
        jumpPressed = false

        // 不按 RB，直接按 A → Jump
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())
        assertTrue(jumpPressed)
        assertFalse(targetPressed)
    }

    @Test
    fun `操作层覆盖完整流程`() {
        val commonLayer = ActionSet("__common__")
        var jumpPressed = false
        var slot5Pressed = false

        commonLayer.addButtonAction("Jump") { onPressed = { jumpPressed = true } }
        commonLayer.addButtonAction("Slot5") { onPressed = { slot5Pressed = true } }
        commonLayer.buttonBindings[ControllerButton.A] = "Jump"

        val combat = ActionSetLayer("Combat")
        combat.overrideButtonBinding(ControllerButton.A, "Slot5")

        val heldButtons = mutableSetOf<ControllerButton>()

        // 无层激活: A → Jump
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, emptyList())
        assertTrue(jumpPressed)
        assertFalse(slot5Pressed)

        // 释放 A
        dispatchButton(ControllerButton.A, false, heldButtons, commonLayer, emptyList())
        jumpPressed = false

        // 激活 Combat 层: A → Slot5
        dispatchButton(ControllerButton.A, true, heldButtons, commonLayer, listOf(combat))
        assertFalse(jumpPressed)
        assertTrue(slot5Pressed)
    }

    // ===== D-Pad 组合键场景测试 =====

    @Test
    fun `D-Pad加L3组合键`() {
        val commonLayer = ActionSet("__common__")
        commonLayer.buttonBindings[ControllerButton.DPAD_UP] = "Slot1"
        commonLayer.addChordBinding(ControllerButton.DPAD_UP, "Slot5",
            setOf(ControllerButton.LEFT_STICK_CLICK))
        commonLayer.addChordBinding(ControllerButton.DPAD_UP, "Slot9",
            setOf(ControllerButton.RIGHT_STICK_CLICK))

        // 无修饰键 → Slot1
        assertEquals("Slot1", lookupBinding(
            ControllerButton.DPAD_UP, emptySet(), commonLayer, emptyList()))

        // L3 按住 → Slot5
        assertEquals("Slot5", lookupBinding(
            ControllerButton.DPAD_UP,
            setOf(ControllerButton.LEFT_STICK_CLICK),
            commonLayer, emptyList()))

        // R3 按住 → Slot9
        assertEquals("Slot9", lookupBinding(
            ControllerButton.DPAD_UP,
            setOf(ControllerButton.RIGHT_STICK_CLICK),
            commonLayer, emptyList()))
    }
}
