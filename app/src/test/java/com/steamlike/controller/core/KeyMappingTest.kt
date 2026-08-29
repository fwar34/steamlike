package com.steamlike.controller.core // 包声明：控制器核心组件所在的包

import org.junit.Assert.assertEquals // 导入JUnit断言函数：断言相等
import org.junit.Assert.assertNull // 导入JUnit断言函数：断言为空
import org.junit.Assert.assertTrue // 导入JUnit断言函数：断言为真
import org.junit.Test // 导入JUnit测试注解 @Test

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
class KeyMappingTest { // 声明测试类 KeyMappingTest，测试按键映射与动作描述

    // KeyCode 常量（对应 Android KeyEvent.KEYCODE_*）
    private val KC_A = 29 // 语法：private val 私有只读常量；A 键键码 29
    private val KC_B = 30 // B 键键码 30
    private val KC_C = 31 // C 键键码 31
    private val KC_D = 32 // D 键键码 32
    private val KC_0 = 7 // 数字 0 键键码 7
    private val KC_9 = 16 // 数字 9 键键码 16
    private val KC_F1 = 131 // F1 键键码 131
    private val KC_F12 = 142 // F12 键键码 142
    private val KC_SHIFT = 59 // Shift 键键码 59
    private val KC_CTRL = 113 // Ctrl 键键码 113
    private val KC_ALT = 57 // Alt 键键码 57
    private val KC_SPACE = 62 // 空格键键码 62
    private val KC_ENTER = 66 // 回车键键码 66
    private val KC_ESC = 111 // Esc 键键码 111

    // ===== 子命令限制测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `子命令最多3个`() { // 测试用例：子命令数量上限为 3
        val mapping = KeyMapping( // 语法：val 声明只读变量；构造 KeyMapping 对象
            MappedAction.KeyboardKey(KC_A), // 第一个参数：主动作，A 键对应的键盘按键动作
            listOf(KC_B, KC_C, KC_D) // 第二个参数：语法：listOf() 构造子命令列表，含 3 个键码
        ) // 结束 KeyMapping 构造
        assertEquals(3, mapping.subCommands.size) // 断言子命令数量为 3
    } // 结束「子命令最多3个」测试方法

    @Test(expected = IllegalArgumentException::class) // 语法：@Test(expected=...) 断言该方法应抛出 IllegalArgumentException 异常
    fun `超过3个子命令抛异常`() { // 测试用例：超过 3 个子命令应抛异常
        KeyMapping( // 构造 KeyMapping 对象（未赋给变量，直接创建以触发校验）
            MappedAction.KeyboardKey(KC_A), // 第一个参数：主动作，A 键的键盘按键动作
            listOf(1, 2, 3, 4) // 第二个参数：4 个子命令，超过上限 3，应触发异常
        ) // 结束 KeyMapping 构造
    } // 结束「超过3个子命令抛异常」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `无子命令时subCommands为空`() { // 测试用例：不传子命令时列表应为空
        val mapping = KeyMapping(MappedAction.KeyboardKey(KC_A)) // 语法：val 声明只读变量；构造仅含主动作的 KeyMapping，无子命令
        assertTrue(mapping.subCommands.isEmpty()) // 断言子命令列表为空
    } // 结束「无子命令时subCommands为空」测试方法

    // ===== keyCodeToName 测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `字母转换`() { // 测试用例：键码到字母名称的转换
        assertEquals("A", KeyMapping.keyCodeToName(KC_A)) // 断言键码 29 转换为 "A"
        assertEquals("Z", KeyMapping.keyCodeToName(54)) // 断言键码 54 转换为 "Z"
    } // 结束「字母转换」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `数字转换`() { // 测试用例：键码到数字名称的转换
        assertEquals("0", KeyMapping.keyCodeToName(KC_0)) // 断言键码 7 转换为 "0"
        assertEquals("9", KeyMapping.keyCodeToName(KC_9)) // 断言键码 16 转换为 "9"
    } // 结束「数字转换」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `功能键转换`() { // 测试用例：键码到功能键名称的转换
        assertEquals("F1", KeyMapping.keyCodeToName(KC_F1)) // 断言键码 131 转换为 "F1"
        assertEquals("F12", KeyMapping.keyCodeToName(KC_F12)) // 断言键码 142 转换为 "F12"
    } // 结束「功能键转换」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `修饰键转换`() { // 测试用例：键码到修饰键名称的转换
        assertEquals("Shift", KeyMapping.keyCodeToName(KC_SHIFT)) // 断言键码 59 转换为 "Shift"
        assertEquals("Ctrl", KeyMapping.keyCodeToName(KC_CTRL)) // 断言键码 113 转换为 "Ctrl"
        assertEquals("Alt", KeyMapping.keyCodeToName(KC_ALT)) // 断言键码 57 转换为 "Alt"
    } // 结束「修饰键转换」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `特殊键转换`() { // 测试用例：键码到特殊键名称的转换
        assertEquals("Space", KeyMapping.keyCodeToName(KC_SPACE)) // 断言键码 62 转换为 "Space"
        assertEquals("Enter", KeyMapping.keyCodeToName(KC_ENTER)) // 断言键码 66 转换为 "Enter"
        assertEquals("Esc", KeyMapping.keyCodeToName(KC_ESC)) // 断言键码 111 转换为 "Esc"
    } // 结束「特殊键转换」测试方法

    // ===== describe() 测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `describe键盘按键无子命令`() { // 测试用例：无子命令的键盘按键描述字符串
        val mapping = KeyMapping(MappedAction.KeyboardKey(KC_A)) // 语法：val 声明只读变量；构造 A 键映射，无子命令
        assertEquals("A", mapping.describe()) // 断言描述字符串为 "A"
    } // 结束「describe键盘按键无子命令」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `describe键盘按键带子命令`() { // 测试用例：带子命令的键盘按键描述字符串
        val mapping = KeyMapping( // 语法：val 声明只读变量；构造 KeyMapping 对象
            MappedAction.KeyboardKey(KC_ALT), // 第一个参数：主动作，Alt 键
            listOf(KC_9)  // 数字9 // 第二个参数：语法：listOf() 子命令列表，含数字 9 键
        ) // 结束 KeyMapping 构造
        assertEquals("Alt+9", mapping.describe()) // 断言描述字符串为 "Alt+9"，用 + 连接主动作与子命令
    } // 结束「describe键盘按键带子命令」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `describe多子命令`() { // 测试用例：多个子命令时的描述字符串
        val mapping = KeyMapping( // 语法：val 声明只读变量；构造 KeyMapping 对象
            MappedAction.KeyboardKey(KC_CTRL), // 第一个参数：主动作，Ctrl 键
            listOf(KC_SHIFT, KC_9) // 第二个参数：语法：listOf() 子命令列表，含 Shift 和 9
        ) // 结束 KeyMapping 构造
        assertEquals("Ctrl+Shift+9", mapping.describe()) // 断言描述字符串为 "Ctrl+Shift+9"，按顺序用 + 连接
    } // 结束「describe多子命令」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `describe鼠标点击`() { // 测试用例：鼠标点击动作的描述字符串
        val mapping = KeyMapping(MappedAction.MouseClick(MouseButton.LEFT)) // 语法：val 声明只读变量；构造鼠标左键点击映射
        assertEquals("鼠标左键", mapping.describe()) // 断言描述字符串为「鼠标左键」
    } // 结束「describe鼠标点击」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `describe切换层`() { // 测试用例：切换层动作的描述字符串
        val mapping = KeyMapping(MappedAction.SwitchLayer("Layer1")) // 语法：val 声明只读变量；构造切换到 Layer1 的映射
        assertEquals("切换→Layer1", mapping.describe()) // 断言描述字符串为「切换→Layer1」
    } // 结束「describe切换层」测试方法

    // ===== MappedAction 类型测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `KeyboardKey持有keyCode`() { // 测试用例：KeyboardKey 动作应保存键码
        val action = MappedAction.KeyboardKey(KC_SPACE) // 语法：val 声明只读变量；构造空格键的 KeyboardKey 动作
        assertEquals(KC_SPACE, action.keyCode) // 断言动作保存的键码为空格键 62
    } // 结束「KeyboardKey持有keyCode」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `MouseClick持有button`() { // 测试用例：MouseClick 动作应保存鼠标按键
        val action = MappedAction.MouseClick(MouseButton.RIGHT) // 语法：val 声明只读变量；构造右键点击动作
        assertEquals(MouseButton.RIGHT, action.button) // 断言动作保存的鼠标按键为右键
    } // 结束「MouseClick持有button」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `SwitchLayer持有layerName`() { // 测试用例：SwitchLayer 动作应保存层名
        val action = MappedAction.SwitchLayer("Combat") // 语法：val 声明只读变量；构造切换到 Combat 层的动作
        assertEquals("Combat", action.layerName) // 断言动作保存的层名为 "Combat"
    } // 结束「SwitchLayer持有layerName」测试方法
} // 结束 KeyMappingTest 测试类
