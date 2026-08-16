package com.steamlike.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChordBinding 组合键绑定测试
 *
 * 测试内容:
 * - matches() 匹配逻辑
 * - chordSize 属性
 * - 空chord、单键chord、多键chord
 * - 部分匹配、超集匹配
 */
class ChordBindingTest {

    // ===== matches 测试 =====

    @Test
    fun `空chord匹配任何heldButtons`() {
        val binding = ChordBinding(ControllerButton.A, "Jump", emptySet())
        assertTrue(binding.matches(emptySet()))
        assertTrue(binding.matches(setOf(ControllerButton.B)))
        assertTrue(binding.matches(setOf(ControllerButton.A, ControllerButton.B)))
    }

    @Test
    fun `单键chord匹配包含该键的集合`() {
        val binding = ChordBinding(
            ControllerButton.A, "Slot5",
            setOf(ControllerButton.RIGHT_SHOULDER)
        )
        assertTrue(binding.matches(setOf(ControllerButton.RIGHT_SHOULDER)))
        assertTrue(binding.matches(setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_SHOULDER)))
    }

    @Test
    fun `单键chord不匹配不含该键的集合`() {
        val binding = ChordBinding(
            ControllerButton.A, "Slot5",
            setOf(ControllerButton.RIGHT_SHOULDER)
        )
        assertFalse(binding.matches(emptySet()))
        assertFalse(binding.matches(setOf(ControllerButton.LEFT_SHOULDER)))
    }

    @Test
    fun `多键chord需要所有修饰键都按住`() {
        val binding = ChordBinding(
            ControllerButton.A, "Potion",
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK)
        )
        // 两个修饰键都按住
        assertTrue(binding.matches(setOf(
            ControllerButton.RIGHT_SHOULDER,
            ControllerButton.LEFT_TRIGGER_CLICK
        )))
        // 两个修饰键 + 额外按钮
        assertTrue(binding.matches(setOf(
            ControllerButton.RIGHT_SHOULDER,
            ControllerButton.LEFT_TRIGGER_CLICK,
            ControllerButton.B
        )))
    }

    @Test
    fun `多键chord部分匹配时返回false`() {
        val binding = ChordBinding(
            ControllerButton.A, "Potion",
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK)
        )
        // 只按住一个修饰键
        assertFalse(binding.matches(setOf(ControllerButton.RIGHT_SHOULDER)))
        assertFalse(binding.matches(setOf(ControllerButton.LEFT_TRIGGER_CLICK)))
        // 空集合
        assertFalse(binding.matches(emptySet()))
    }

    // ===== chordSize 测试 =====

    @Test
    fun `空chord的chordSize为0`() {
        val binding = ChordBinding(ControllerButton.A, "Jump", emptySet())
        assertEquals(0, binding.chordSize)
    }

    @Test
    fun `单键chord的chordSize为1`() {
        val binding = ChordBinding(
            ControllerButton.A, "Slot5",
            setOf(ControllerButton.RIGHT_SHOULDER)
        )
        assertEquals(1, binding.chordSize)
    }

    @Test
    fun `多键chord的chordSize等于修饰键数量`() {
        val binding = ChordBinding(
            ControllerButton.A, "Potion",
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK, ControllerButton.B)
        )
        assertEquals(3, binding.chordSize)
    }

    // ===== 优先级排序测试 =====

    @Test
    fun `chordSize大的绑定优先级更高`() {
        val defaultBinding = ChordBinding(ControllerButton.A, "Jump", emptySet())
        val chord1 = ChordBinding(ControllerButton.A, "Slot5", setOf(ControllerButton.RIGHT_SHOULDER))
        val chord2 = ChordBinding(ControllerButton.A, "Potion",
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK))

        // 模拟 getEffectiveButtonBinding 的选择逻辑
        val heldButtons = setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK)
        val allBindings = listOf(defaultBinding, chord1, chord2)

        var best: ChordBinding? = null
        for (cb in allBindings) {
            if (cb.button != ControllerButton.A) continue
            if (cb.chord.isEmpty()) continue
            if (!cb.matches(heldButtons)) continue
            if (best == null || cb.chordSize > best.chordSize) {
                best = cb
            }
        }

        assertEquals("Potion", best?.actionName)
    }

    @Test
    fun `无修饰键时chordSize大的不匹配`() {
        val chord1 = ChordBinding(ControllerButton.A, "Slot5", setOf(ControllerButton.RIGHT_SHOULDER))
        val chord2 = ChordBinding(ControllerButton.A, "Potion",
            setOf(ControllerButton.RIGHT_SHOULDER, ControllerButton.LEFT_TRIGGER_CLICK))

        // 只按住 RB，chord2 需要 RB+LT，不匹配
        val heldButtons = setOf(ControllerButton.RIGHT_SHOULDER)
        val allBindings = listOf(chord1, chord2)

        var best: ChordBinding? = null
        for (cb in allBindings) {
            if (cb.button != ControllerButton.A) continue
            if (cb.chord.isEmpty()) continue
            if (!cb.matches(heldButtons)) continue
            if (best == null || cb.chordSize > best.chordSize) {
                best = cb
            }
        }

        assertEquals("Slot5", best?.actionName)
    }
}
