package com.steamlike.controller.core // 包声明：控制器核心组件所在的包

import org.junit.Assert.assertEquals // 导入JUnit断言函数：断言相等
import org.junit.Assert.assertNotNull // 导入JUnit断言函数：断言非空
import org.junit.Assert.assertNull // 导入JUnit断言函数：断言为空
import org.junit.Assert.assertTrue // 导入JUnit断言函数：断言为真
import org.junit.Test // 导入JUnit测试注解 @Test

/**
 * OperationLayer 和 ControllerProfile 测试
 *
 * 测试内容:
 * - OperationLayer 按键映射管理
 * - ControllerProfile 层查找
 * - ControllerProfile.createDefault() 默认配置
 * - 层回退查询逻辑
 *
 * 注意: 使用整数常量而非 Android KeyEvent 常量，以便纯 JVM 测试。
 */
class OperationLayerTest { // 声明测试类 OperationLayerTest，测试操作层与配置档案

    // KeyCode 常量
    private val KC_A = 29 // 语法：private val 私有只读常量；A 键键码 29
    private val KC_B = 30 // B 键键码 30

    // ===== OperationLayer 测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `创建空层`() { // 测试用例：创建一个空的 OperationLayer
        val layer = OperationLayer("Test") // 语法：val 声明只读变量；构造名为 "Test" 的操作层
        assertEquals("Test", layer.name) // 断言层的名称为 "Test"
        assertTrue(layer.buttonMappings.isEmpty()) // 断言新层的按键映射表为空
    } // 结束「创建空层」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `添加和查询映射`() { // 测试用例：向层中添加映射并可查询
        val layer = OperationLayer("Test") // 语法：val 声明只读变量；构造名为 "Test" 的操作层
        val mapping = KeyMapping(MappedAction.KeyboardKey(KC_A)) // 语法：val 声明只读变量；构造 A 键的键盘按键映射
        layer.buttonMappings[ControllerButton.A] = mapping // 语法：map[key]=value 键值赋值；把映射放入 A 按钮的映射表
        assertEquals(mapping, layer.getMapping(ControllerButton.A)) // 断言 A 按钮查询到刚添加的映射（对象相等）
        assertNull(layer.getMapping(ControllerButton.B)) // 断言 B 按钮无映射，返回 null
    } // 结束「添加和查询映射」测试方法

    // ===== ControllerProfile 测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `createDefault创建10个层`() { // 测试用例：createDefault() 应创建 10 个操作层
        val profile = ControllerProfile.createDefault() // 语法：val 声明只读变量；调用伴生函数 createDefault() 构造默认档案
        assertEquals(10, profile.layers.size) // 断言操作层数量为 10
        assertEquals("Common", profile.commonLayer.name) // 断言公共层名为 "Common"
    } // 结束「createDefault创建10个层」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `createDefault公共层有默认映射`() { // 测试用例：默认公共层应包含预设按键映射
        val profile = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认档案
        assertNotNull(profile.commonLayer.getMapping(ControllerButton.A)) // 断言公共层 A 按钮有默认映射（非空）
        assertNotNull(profile.commonLayer.getMapping(ControllerButton.B)) // 断言公共层 B 按钮有默认映射（非空）
    } // 结束「createDefault公共层有默认映射」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `createDefault层名正确`() { // 测试用例：默认层的命名应正确
        val profile = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认档案
        assertEquals("Layer1", profile.layers[0].name) // 断言第 1 个操作层名为 "Layer1"（下标0）
        assertEquals("Layer10", profile.layers[9].name) // 断言第 10 个操作层名为 "Layer10"（下标9）
    } // 结束「createDefault层名正确」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `createDefault公共层切入按键正确分配`() { // 测试用例：默认公共层中每个切入按键应正确分配
        val profile = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认档案
        val common = profile.commonLayer // 语法：val 声明只读变量；取出公共层的引用
        fun switchInLayer(button: ControllerButton): String? = // 语法：fun 声明局部函数；返回该按钮切入的层名（可能为 null）
            (common.getMapping(button)?.action as? MappedAction.SwitchLayer)?.layerName // 语法：?. 空安全调用 + as? 安全转型；取切入层名

        assertEquals("Layer1", switchInLayer(ControllerButton.DPAD_UP)) // 断言方向上键切入 Layer1
        assertEquals("Layer2", switchInLayer(ControllerButton.DPAD_DOWN)) // 断言方向下键切入 Layer2
        assertEquals("Layer3", switchInLayer(ControllerButton.DPAD_LEFT)) // 断言方向左键切入 Layer3
        assertEquals("Layer4", switchInLayer(ControllerButton.DPAD_RIGHT)) // 断言方向右键切入 Layer4
        assertEquals("Layer5", switchInLayer(ControllerButton.LEFT_SHOULDER)) // 断言左肩键切入 Layer5
        assertEquals("Layer6", switchInLayer(ControllerButton.RIGHT_SHOULDER)) // 断言右肩键切入 Layer6
        assertEquals("Layer7", switchInLayer(ControllerButton.LEFT_STICK_CLICK)) // 断言左摇杆按下切入 Layer7
        // Layer8 切入键为 Touchpad（R3 保留为 LookAround 视角控制，不作为层切换键）
        assertEquals("Layer8", switchInLayer(ControllerButton.TOUCHPAD_CLICK)) // 断言触摸板点击切入 Layer8
        assertEquals("Layer9", switchInLayer(ControllerButton.LEFT_TRIGGER_CLICK)) // 断言左扳机点击切入 Layer9
        assertEquals("Layer10", switchInLayer(ControllerButton.RIGHT_TRIGGER_CLICK)) // 断言右扳机点击切入 Layer10
    } // 结束「createDefault公共层切入按键正确分配」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `allLayers包含公共层和操作层`() { // 测试用例：allLayers 应包含公共层和全部操作层
        val profile = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认档案
        assertEquals(11, profile.allLayers.size) // Common + 10 layers // 断言总层数为 11（公共层+10操作层）
        assertEquals("Common", profile.allLayers[0].name) // 断言 allLayers 第一个为 "Common"
        assertEquals("Layer1", profile.allLayers[1].name) // 断言 allLayers 第二个为 "Layer1"
    } // 结束「allLayers包含公共层和操作层」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `findLayer按名称查找`() { // 测试用例：findLayer 应按名称查找层
        val profile = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认档案
        assertNotNull(profile.findLayer("Common")) // 断言能查找到 "Common"
        assertNotNull(profile.findLayer("Layer1")) // 断言能查找到 "Layer1"
        assertNotNull(profile.findLayer("Layer10")) // 断言能查找到 "Layer10"
        assertNull(profile.findLayer("NotExist")) // 断言查找不存在的层返回 null
    } // 结束「findLayer按名称查找」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `findLayerBySwitchIn公共层按切入键查找`() { // 测试用例：按切入键在公共层中查找目标层
        val profile = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认档案
        // 公共层中查找指向 Layer1 的切入按键（SwitchLayer 映射）
        val switchInButton = profile.commonLayer.buttonMappings.entries // 语法：val 声明只读变量；取公共层映射表的全部条目
            .firstOrNull { (button, mapping) -> // 语法：lambda {参数->表达式}；firstOrNull 返回第一个满足条件的条目，否则 null
                (mapping.action as? MappedAction.SwitchLayer)?.layerName == "Layer1" // 语法：as? 安全转型 + == 比较；筛选切入 Layer1 的映射
            } // 结束 firstOrNull 的 lambda
            ?.key // 语法：?. 空安全调用 + key 取条目键；若找到则取按钮，否则 null
        assertEquals(ControllerButton.DPAD_UP, switchInButton) // 断言切入 Layer1 的按钮为方向上键
    } // 结束「findLayerBySwitchIn公共层按切入键查找」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `findLayerBySwitchIn公共层无映射返回null`() { // 测试用例：公共层中无对应切入映射时应返回 null
        val profile = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认档案
        // 公共层中无指向不存在层的切入按键
        val switchInButton = profile.commonLayer.buttonMappings.entries // 语法：val 声明只读变量；取公共层映射表的全部条目
            .firstOrNull { (button, mapping) -> // 语法：lambda {参数->表达式}；firstOrNull 返回第一个满足条件的条目，否则 null
                (mapping.action as? MappedAction.SwitchLayer)?.layerName == "NotExist" // 语法：as? 安全转型 + == 比较；筛选切入 NotExist 的映射（不存在）
            } // 结束 firstOrNull 的 lambda
            ?.key // 语法：?. 空安全调用 + key 取条目键；无匹配时整体为 null
        assertNull(switchInButton) // 断言结果为空
    } // 结束「findLayerBySwitchIn公共层无映射返回null」测试方法

    // ===== 全局设置测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `默认全局设置死区为015`() { // 测试用例：默认全局设置死区应为 0.15
        val settings = GlobalSettings() // 语法：val 声明只读变量；构造默认全局设置对象
        assertEquals(0.15f, settings.deadzone, 0.001f) // 断言默认死区为 0.15f（delta=0.001f 误差允许）
    } // 结束「默认全局设置死区为015」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `默认全局设置灵敏度为05`() { // 测试用例：默认全局设置灵敏度应为 0.5
        val settings = GlobalSettings() // 语法：val 声明只读变量；构造默认全局设置对象
        assertEquals(0.5f, settings.lookSensitivity, 0.001f) // 断言默认视角灵敏度为 0.5f（delta=0.001f）
        assertEquals(1.0f, settings.cursorSpeed, 0.001f) // 断言默认光标速度为 1.0f（delta=0.001f）
    } // 结束「默认全局设置灵敏度为05」测试方法

    // ===== 层回退查询逻辑测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `层回退查询 - 操作层有映射时使用操作层`() { // 测试用例：操作层有映射时应优先使用操作层的映射
        val common = OperationLayer("Common") // 语法：val 声明只读变量；构造公共层
        common.buttonMappings[ControllerButton.A] = // 语法：map[key]=value 键值赋值；给公共层 A 按钮设置映射
            KeyMapping(MappedAction.KeyboardKey(KC_A)) // 语法：赋值内容，构造 A 键的键盘按键映射

        val layer1 = OperationLayer("Layer1") // 语法：val 声明只读变量；构造操作层 Layer1
        layer1.buttonMappings[ControllerButton.A] = // 语法：map[key]=value 键值赋值；给 Layer1 的 A 按钮设置映射
            KeyMapping(MappedAction.KeyboardKey(KC_B)) // 语法：赋值内容，构造 B 键的键盘按键映射

        // 模拟查询: 激活Layer1时查A（本测试中 Layer1 必有映射，结果非空）
        // 语法：?: 空值合并（左侧为空则取右侧）+ !! 非空断言；Layer1 优先，回退公共层
        val mapping = (layer1.getMapping(ControllerButton.A) ?: common.getMapping(ControllerButton.A))!!
        assertEquals(KC_B, (mapping.action as MappedAction.KeyboardKey).keyCode) // 断言取到的是 Layer1 的映射（键码 KC_B=30）
    } // 结束「层回退查询 - 操作层有映射时使用操作层」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `层回退查询 - 操作层无映射时回退公共层`() { // 测试用例：操作层无映射时应回退到公共层
        val common = OperationLayer("Common") // 语法：val 声明只读变量；构造公共层
        common.buttonMappings[ControllerButton.A] = // 语法：map[key]=value 键值赋值；给公共层 A 按钮设置映射
            KeyMapping(MappedAction.KeyboardKey(KC_A)) // 语法：赋值内容，构造 A 键的键盘按键映射

        val layer1 = OperationLayer("Layer1") // 语法：val 声明只读变量；构造操作层 Layer1
        // Layer1 没有A的映射

        // 模拟查询: 激活Layer1时查A，Layer1没有则回退Common（公共层必有映射，结果非空）
        // 语法：?: 空值合并（左侧为空则取右侧）+ !! 非空断言；Layer1 无映射时回退公共层
        val mapping = (layer1.getMapping(ControllerButton.A) ?: common.getMapping(ControllerButton.A))!!
        assertEquals(KC_A, (mapping.action as MappedAction.KeyboardKey).keyCode) // 断言回退取到的是公共层的映射（键码 KC_A=29）
    } // 结束「层回退查询 - 操作层无映射时回退公共层」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `层回退查询 - 都没映射返回null`() { // 测试用例：两层都无映射时应返回 null
        val common = OperationLayer("Common") // 语法：val 声明只读变量；构造公共层（无映射）
        val layer1 = OperationLayer("Layer1") // 语法：val 声明只读变量；构造操作层 Layer1（无映射）

        val mapping = layer1.getMapping(ControllerButton.A) ?: common.getMapping(ControllerButton.A) // 语法：?: 空值合并；两层都无映射时为 null
        assertNull(mapping) // 断言结果为 null
    } // 结束「层回退查询 - 都没映射返回null」测试方法
} // 结束 OperationLayerTest 测试类
