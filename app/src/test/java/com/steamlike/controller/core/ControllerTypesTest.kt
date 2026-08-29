package com.steamlike.controller.core // 包声明：控制器核心组件所在的包

import org.junit.Assert.assertEquals // 导入JUnit断言函数：断言相等
import org.junit.Assert.assertFalse // 导入JUnit断言函数：断言为假
import org.junit.Assert.assertTrue // 导入JUnit断言函数：断言为真
import org.junit.Test // 导入JUnit测试注解 @Test

/**
 * ControllerTypes 枚举与数据结构测试
 *
 * 测试内容:
 * - ControllerType.fromVendorProduct (手柄类型识别)
 * - ControllerState 状态查询
 * - ControllerButton 枚举完整性
 */
class ControllerTypesTest { // 声明测试类 ControllerTypesTest，集中测试手柄类型相关类型

    // ===== ControllerType 识别测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `识别Xbox 360手柄`() { // 测试用例：识别 Xbox 360 手柄
        val type = ControllerType.fromVendorProduct(0x045E, 0x028E) // 语法：val 声明只读变量；用厂商ID 0x045E + 产品ID 0x028E 识别手柄类型
        assertEquals(ControllerType.XBOX_360, type) // 语法：assertEquals(期望,实际)；断言识别结果为 XBOX_360
    } // 结束「识别Xbox 360手柄」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `识别Xbox One手柄`() { // 测试用例：识别 Xbox One 手柄
        val type = ControllerType.fromVendorProduct(0x045E, 0x02DD) // 语法：val 声明只读变量；用厂商ID 0x045E + 产品ID 0x02DD 识别手柄类型
        assertEquals(ControllerType.XBOX_ONE, type) // 断言识别结果为 XBOX_ONE
    } // 结束「识别Xbox One手柄」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `识别PS4手柄`() { // 测试用例：识别 PS4 手柄
        val type = ControllerType.fromVendorProduct(0x054C, 0x05C4) // 语法：val 声明只读变量；用厂商ID 0x054C + 产品ID 0x05C4 识别手柄类型
        assertEquals(ControllerType.PS4, type) // 断言识别结果为 PS4
    } // 结束「识别PS4手柄」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `识别PS5手柄`() { // 测试用例：识别 PS5 手柄
        val type = ControllerType.fromVendorProduct(0x054C, 0x0CE6) // 语法：val 声明只读变量；用厂商ID 0x054C + 产品ID 0x0CE6 识别手柄类型
        assertEquals(ControllerType.PS5_DUALSENSE, type) // 断言识别结果为 PS5_DUALSENSE
    } // 结束「识别PS5手柄」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `识别Switch Pro手柄`() { // 测试用例：识别 Switch Pro 手柄
        val type = ControllerType.fromVendorProduct(0x057E, 0x2009) // 语法：val 声明只读变量；用厂商ID 0x057E + 产品ID 0x2009 识别手柄类型
        assertEquals(ControllerType.SWITCH_PRO, type) // 断言识别结果为 SWITCH_PRO
    } // 结束「识别Switch Pro手柄」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `识别Steam Deck`() { // 测试用例：识别 Steam Deck
        val type = ControllerType.fromVendorProduct(0x28DE, 0x1205) // 语法：val 声明只读变量；用厂商ID 0x28DE + 产品ID 0x1205 识别手柄类型
        assertEquals(ControllerType.STEAM_DECK, type) // 断言识别结果为 STEAM_DECK
    } // 结束「识别Steam Deck」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `未知VID_PID返回GENERIC`() { // 测试用例：未知 VID/PID 组合应返回 GENERIC
        val type = ControllerType.fromVendorProduct(0x1234, 0x5678) // 语法：val 声明只读变量；用未注册的厂商ID 0x1234 + 产品ID 0x5678 识别
        assertEquals(ControllerType.GENERIC, type) // 断言未知设备识别为 GENERIC 通用类型
    } // 结束「未知VID_PID返回GENERIC」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `同VID不同PID返回正确类型`() { // 测试用例：同一厂商ID下不同产品ID应返回正确型号
        // Xbox 360 和 Xbox One 都是 0x045E，但 PID 不同
        assertEquals(ControllerType.XBOX_360, ControllerType.fromVendorProduct(0x045E, 0x028E)) // 断言 0x045E+0x028E 识别为 XBOX_360
        assertEquals(ControllerType.XBOX_ONE, ControllerType.fromVendorProduct(0x045E, 0x02DD)) // 断言 0x045E+0x02DD 识别为 XBOX_ONE
        assertEquals(ControllerType.XBOX_ELITE, ControllerType.fromVendorProduct(0x045E, 0x0B00)) // 断言 0x045E+0x0B00 识别为 XBOX_ELITE
    } // 结束「同VID不同PID返回正确类型」测试方法

    // ===== ControllerState 测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `ControllerState查询按钮状态`() { // 测试用例：查询 ControllerState 中按钮的按下状态
        val state = ControllerState( // 语法：val 声明只读变量；构造 ControllerState 对象
            deviceId = 1, // 命名参数 deviceId：设备ID=1
            timestamp = 1000, // 命名参数 timestamp：时间戳=1000
            buttons = mapOf( // 命名参数 buttons：语法：mapOf() 构造键值对映射，记录各按钮状态
                ControllerButton.A to true, // 语法：Pair 写法 "A 按钮 -> true"，表示 A 已按下
                ControllerButton.B to false, // B 按钮 -> false，表示 B 未按下
                ControllerButton.X to true // X 按钮 -> true，表示 X 已按下
            ) // 结束 mapOf 参数
        ) // 结束 ControllerState 构造
        assertTrue(state.isButtonPressed(ControllerButton.A)) // 语法：assertTrue 断言为真；断言 A 处于按下状态
        assertFalse(state.isButtonPressed(ControllerButton.B)) // 语法：assertFalse 断言为假；断言 B 未被按下
        assertTrue(state.isButtonPressed(ControllerButton.X)) // 断言 X 处于按下状态
        assertFalse(state.isButtonPressed(ControllerButton.Y))  // 未设置 = false // 断言 Y 未设置时返回 false
    } // 结束「ControllerState查询按钮状态」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `ControllerState查询摇杆位置`() { // 测试用例：查询 ControllerState 中摇杆的位置
        val state = ControllerState( // 语法：val 声明只读变量；构造 ControllerState 对象
            deviceId = 1, // 命名参数 deviceId：设备ID=1
            timestamp = 1000, // 命名参数 timestamp：时间戳=1000
            sticks = mapOf( // 命名参数 sticks：语法：mapOf() 构造摇杆映射
                ControllerStick.LEFT_STICK to Vector2(0.5f, 0.3f) // 语法：Pair 写法 左摇杆 -> 向量(0.5,0.3)；0.5f 为 Float 字面量
            ) // 结束 mapOf 参数
        ) // 结束 ControllerState 构造
        val leftStick = state.getStick(ControllerStick.LEFT_STICK) // 语法：val 声明只读变量；获取左摇杆向量
        assertEquals(0.5f, leftStick.x, 0.001f) // 语法：assertEquals(期望,实际,delta)；delta=0.001f 为浮点误差允许范围；断言 x=0.5
        assertEquals(0.3f, leftStick.y, 0.001f) // 断言 y=0.3（delta=0.001f 误差允许）

        // 未设置的摇杆返回零向量
        val rightStick = state.getStick(ControllerStick.RIGHT_STICK) // 语法：val 声明只读变量；获取未设置的右摇杆
        assertEquals(Vector2.ZERO, rightStick) // 断言未设置的右摇杆返回零向量 Vector2.ZERO
    } // 结束「ControllerState查询摇杆位置」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `ControllerState查询扳机值`() { // 测试用例：查询 ControllerState 中扳机的值
        val state = ControllerState( // 语法：val 声明只读变量；构造 ControllerState 对象
            deviceId = 1, // 命名参数 deviceId：设备ID=1
            timestamp = 1000, // 命名参数 timestamp：时间戳=1000
            triggers = mapOf( // 命名参数 triggers：语法：mapOf() 构造扳机映射
                ControllerTrigger.LEFT_TRIGGER to 0.7f // 语法：Pair 写法 左扳机 -> 0.7；0.7f 为 Float 字面量
            ) // 结束 mapOf 参数
        ) // 结束 ControllerState 构造
        assertEquals(0.7f, state.getTrigger(ControllerTrigger.LEFT_TRIGGER), 0.001f) // 断言左扳机值=0.7（delta=0.001f 误差允许）
        assertEquals(0f, state.getTrigger(ControllerTrigger.RIGHT_TRIGGER), 0.001f) // 断言未设置的右扳机默认值=0
    } // 结束「ControllerState查询扳机值」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `空ControllerState所有查询返回默认值`() { // 测试用例：空 ControllerState 的所有查询应返回默认值
        val state = ControllerState(deviceId = 1, timestamp = 0) // 语法：val 声明只读变量；构造不携带任何映射的空 ControllerState
        assertFalse(state.isButtonPressed(ControllerButton.A)) // 断言空状态下 A 按钮未按下
        assertEquals(Vector2.ZERO, state.getStick(ControllerStick.LEFT_STICK)) // 断言空状态下左摇杆返回零向量
        assertEquals(0f, state.getTrigger(ControllerTrigger.LEFT_TRIGGER), 0.001f) // 断言空状态下左扳机值=0
    } // 结束「空ControllerState所有查询返回默认值」测试方法

    // ===== 枚举完整性测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `ControllerButton包含所有标准按键`() { // 测试用例：ControllerButton 枚举应包含所有标准按键
        val buttons = ControllerButton.values() // 语法：val 声明只读变量；values() 返回枚举的全部常量数组
        // 面部按钮
        assertTrue(buttons.contains(ControllerButton.A)) // 断言枚举包含 A 按钮
        assertTrue(buttons.contains(ControllerButton.B)) // 断言枚举包含 B 按钮
        assertTrue(buttons.contains(ControllerButton.X)) // 断言枚举包含 X 按钮
        assertTrue(buttons.contains(ControllerButton.Y)) // 断言枚举包含 Y 按钮
        // 肩键
        assertTrue(buttons.contains(ControllerButton.LEFT_SHOULDER)) // 断言枚举包含左肩键
        assertTrue(buttons.contains(ControllerButton.RIGHT_SHOULDER)) // 断言枚举包含右肩键
        // 扳机点击
        assertTrue(buttons.contains(ControllerButton.LEFT_TRIGGER_CLICK)) // 断言枚举包含左扳机点击
        assertTrue(buttons.contains(ControllerButton.RIGHT_TRIGGER_CLICK)) // 断言枚举包含右扳机点击
        // 摇杆点击
        assertTrue(buttons.contains(ControllerButton.LEFT_STICK_CLICK)) // 断言枚举包含左摇杆按下
        assertTrue(buttons.contains(ControllerButton.RIGHT_STICK_CLICK)) // 断言枚举包含右摇杆按下
        // 菜单键
        assertTrue(buttons.contains(ControllerButton.MENU)) // 断言枚举包含 MENU 菜单键
        assertTrue(buttons.contains(ControllerButton.OPTIONS)) // 断言枚举包含 OPTIONS 选项键
        assertTrue(buttons.contains(ControllerButton.GUIDE)) // 断言枚举包含 GUIDE 引导键
        // 方向键
        assertTrue(buttons.contains(ControllerButton.DPAD_UP)) // 断言枚举包含方向键上
        assertTrue(buttons.contains(ControllerButton.DPAD_DOWN)) // 断言枚举包含方向键下
        assertTrue(buttons.contains(ControllerButton.DPAD_LEFT)) // 断言枚举包含方向键左
        assertTrue(buttons.contains(ControllerButton.DPAD_RIGHT)) // 断言枚举包含方向键右
    } // 结束「ControllerButton包含所有标准按键」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `ControllerStick包含左摇杆右摇杆和D-Pad`() { // 测试用例：ControllerStick 枚举应包含左摇杆、右摇杆和 D-Pad
        val sticks = ControllerStick.values() // 语法：val 声明只读变量；values() 返回枚举的全部常量数组
        assertEquals(3, sticks.size) // 断言枚举共 3 个常量
        assertTrue(sticks.contains(ControllerStick.LEFT_STICK)) // 断言包含左摇杆
        assertTrue(sticks.contains(ControllerStick.RIGHT_STICK)) // 断言包含右摇杆
        assertTrue(sticks.contains(ControllerStick.DPAD_AS_STICK)) // 断言包含 D-Pad 作为摇杆的映射
    } // 结束「ControllerStick包含左摇杆右摇杆和D-Pad」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `ControllerTrigger包含左右扳机`() { // 测试用例：ControllerTrigger 枚举应包含左、右扳机
        val triggers = ControllerTrigger.values() // 语法：val 声明只读变量；values() 返回枚举的全部常量数组
        assertEquals(2, triggers.size) // 断言枚举共 2 个常量
        assertTrue(triggers.contains(ControllerTrigger.LEFT_TRIGGER)) // 断言包含左扳机
        assertTrue(triggers.contains(ControllerTrigger.RIGHT_TRIGGER)) // 断言包含右扳机
    } // 结束「ControllerTrigger包含左右扳机」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `InputActionType有三种类型`() { // 测试用例：InputActionType 枚举应有三种类型
        val types = InputActionType.values() // 语法：val 声明只读变量；values() 返回枚举的全部常量数组
        assertEquals(3, types.size) // 断言枚举共 3 个常量
        assertTrue(types.contains(InputActionType.BUTTON)) // 断言包含 BUTTON 按钮类型
        assertTrue(types.contains(InputActionType.ANALOG_TRIGGER)) // 断言包含 ANALOG_TRIGGER 模拟扳机类型
        assertTrue(types.contains(InputActionType.STICK_PAD_GYRO)) // 断言包含 STICK_PAD_GYRO 摇杆/触摸板/陀螺仪类型
    } // 结束「InputActionType有三种类型」测试方法
} // 结束 ControllerTypesTest 测试类
