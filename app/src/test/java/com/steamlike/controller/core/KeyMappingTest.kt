package com.steamlike.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KeyMapping 和 MappedAction 测试
 *
 * 测试内容:
 * - 子命令限制（最多3个）
 * - keyCodeToName 转换
 * - describe() 描述字符串
 * - MappedAction 类型判断
 *
 * 注意: 使用整数常量而非 Android KeyEvent 常量，以便纯 JVM 测试。
 * KeyCode 值: A=29, B=30, C=31, D=32, Z=54, 0=7, 9=16
 * F1=131, F12=142, Shift=59, Ctrl=113, Alt=57, Space=62, Enter=66, Esc=111
 */
class KeyMappingTest {

    // KeyCode 常量（对应 Android KeyEvent.KEYCODE_*）
    private val KC_A = 29
    private val KC_B = 30
    private val KC_C = 31
    private val KC_D = 32
    private val KC_0 = 7
    private val KC_9 = 16
    private val KC_F1 = 131
    private val KC_F12 = 142
    private val KC_SHIFT = 59
    private val KC_CTRL = 113
    private val KC_ALT = 57
    private val KC_SPACE = 62
    private val KC_ENTER = 66
    private val KC_ESC = 111

    // ===== 子命令限制测试 =====

    @Test
    fun `子命令最多3个`() {
        val mapping = KeyMapping(
            MappedAction.KeyboardKey(KC_A),
            listOf(KC_B, KC_C, KC_D)
        )
        assertEquals(3, mapping.subCommands.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `超过3个子命令抛异常`() {
        KeyMapping(
            MappedAction.KeyboardKey(KC_A),
            listOf(1, 2, 3, 4)
        )
    }

    @Test
    fun `无子命令时subCommands为空`() {
        val mapping = KeyMapping(MappedAction.KeyboardKey(KC_A))
        assertTrue(mapping.subCommands.isEmpty())
    }

    // ===== keyCodeToName 测试 =====

    @Test
    fun `字母转换`() {
        assertEquals("A", KeyMapping.keyCodeToName(KC_A))
        assertEquals("Z", KeyMapping.keyCodeToName(54))
    }

    @Test
    fun `数字转换`() {
        assertEquals("0", KeyMapping.keyCodeToName(KC_0))
        assertEquals("9", KeyMapping.keyCodeToName(KC_9))
    }

    @Test
    fun `功能键转换`() {
        assertEquals("F1", KeyMapping.keyCodeToName(KC_F1))
        assertEquals("F12", KeyMapping.keyCodeToName(KC_F12))
    }

    @Test
    fun `修饰键转换`() {
        assertEquals("Shift", KeyMapping.keyCodeToName(KC_SHIFT))
        assertEquals("Ctrl", KeyMapping.keyCodeToName(KC_CTRL))
        assertEquals("Alt", KeyMapping.keyCodeToName(KC_ALT))
    }

    @Test
    fun `特殊键转换`() {
        assertEquals("Space", KeyMapping.keyCodeToName(KC_SPACE))
        assertEquals("Enter", KeyMapping.keyCodeToName(KC_ENTER))
        assertEquals("Esc", KeyMapping.keyCodeToName(KC_ESC))
    }

    // ===== describe() 测试 =====

    @Test
    fun `describe键盘按键无子命令`() {
        val mapping = KeyMapping(MappedAction.KeyboardKey(KC_A))
        assertEquals("A", mapping.describe())
    }

    @Test
    fun `describe键盘按键带子命令`() {
        val mapping = KeyMapping(
            MappedAction.KeyboardKey(KC_ALT),
            listOf(KC_9)  // 数字9
        )
        assertEquals("Alt+9", mapping.describe())
    }

    @Test
    fun `describe多子命令`() {
        val mapping = KeyMapping(
            MappedAction.KeyboardKey(KC_CTRL),
            listOf(KC_SHIFT, KC_9)
        )
        assertEquals("Ctrl+Shift+9", mapping.describe())
    }

    @Test
    fun `describe鼠标点击`() {
        val mapping = KeyMapping(MappedAction.MouseClick(MouseButton.LEFT))
        assertEquals("鼠标左键", mapping.describe())
    }

    @Test
    fun `describe切换层`() {
        val mapping = KeyMapping(MappedAction.SwitchLayer("Layer1"))
        assertEquals("切换→Layer1", mapping.describe())
    }

    // ===== MappedAction 类型测试 =====

    @Test
    fun `KeyboardKey持有keyCode`() {
        val action = MappedAction.KeyboardKey(KC_SPACE)
        assertEquals(KC_SPACE, action.keyCode)
    }

    @Test
    fun `MouseClick持有button`() {
        val action = MappedAction.MouseClick(MouseButton.RIGHT)
        assertEquals(MouseButton.RIGHT, action.button)
    }

    @Test
    fun `SwitchLayer持有layerName`() {
        val action = MappedAction.SwitchLayer("Combat")
        assertEquals("Combat", action.layerName)
    }
}
