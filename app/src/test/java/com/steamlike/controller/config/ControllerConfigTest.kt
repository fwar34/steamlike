package com.steamlike.controller.config // 包声明：控制器配置模块所在的包

import com.steamlike.controller.core.ActionSet // 导入 ActionSet 操作集类
import com.steamlike.controller.core.ControllerButton // 导入 ControllerButton 手柄按键枚举
import com.steamlike.controller.core.ControllerProfile // 导入 ControllerProfile 配置档案类
import com.steamlike.controller.core.GlobalSettings // 导入 GlobalSettings 全局设置类
import com.steamlike.controller.core.KeyMapping // 导入 KeyMapping 按键映射类
import com.steamlike.controller.core.MappedAction // 导入 MappedAction 映射动作类
import com.steamlike.controller.core.MouseButton // 导入 MouseButton 鼠标按键枚举
import com.steamlike.controller.core.OperationLayer // 导入 OperationLayer 操作层类
import org.junit.Assert.assertEquals // 导入JUnit断言函数：断言相等
import org.junit.Assert.assertNotNull // 导入JUnit断言函数：断言非空
import org.junit.Assert.assertNull // 导入JUnit断言函数：断言为空
import org.junit.Assert.assertTrue // 导入JUnit断言函数：断言为真
import org.junit.Test // 导入JUnit测试注解 @Test

/**
 * ControllerConfig JSON 序列化/反序列化测试
 *
 * 测试内容:
 * - 往返测试 (Round-trip): 序列化 → 反序列化 → 验证一致性
 * - 各种动作类型序列化
 * - 子命令序列化
 * - 默认配置序列化
 * - 操作集（多操作集/当前操作集名/版本迁移）
 * - 错误处理
 */
class ControllerConfigTest { // 声明测试类 ControllerConfigTest，测试配置 JSON 序列化/反序列化

    // ===== 辅助构造 =====

    /**
     * 构造单操作集配置（模拟旧 commonLayer/layers 的易用性）
     */
    private fun profile( // 语法：private fun 私有函数声明；定义构造配置档案的辅助函数
        commonLayer: OperationLayer, // 参数：公共层
        layers: List<OperationLayer>, // 参数：操作层列表
        globalSettings: GlobalSettings = GlobalSettings(), // 参数：全局设置，语法：默认参数值，不传时用默认设置
        actionSetName: String = ControllerProfile.DEFAULT_ACTION_SET_NAME // 参数：操作集名，语法：默认参数值，默认用「默认」操作集名常量
    ): ControllerProfile = ControllerProfile( // 语法：表达式体函数（= 直接返回表达式）；构造 ControllerProfile
        // 语法：命名参数 + listOf()；把公共层和操作层包成单个 ActionSet 操作集
        actionSets = listOf(ActionSet(name = actionSetName, commonLayer = commonLayer, layers = layers)),
        globalSettings = globalSettings // 命名参数：全局设置
    ) // 结束 ControllerProfile 构造

    // ===== 往返测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `往返测试 - 简单配置`() { // 测试用例：简单配置的序列化→反序列化往返
        val original = profile( // 语法：val 声明只读变量；调用辅助函数构造原始配置
            commonLayer = OperationLayer("Common").apply { // 语法：.apply{} 作用域函数，可在对象上直接操作；构造公共层
                buttonMappings[ControllerButton.A] = KeyMapping( // 语法：map[key]=value 键值赋值；给 A 按钮设置映射
                    MappedAction.KeyboardKey(29)  // KEYCODE_A = 29 // 参数：A 键键盘按键动作，键码 29
                ) // 结束 KeyMapping 构造
                buttonMappings[ControllerButton.B] = KeyMapping( // 语法：map[key]=value 键值赋值；给 B 按钮设置映射
                    MappedAction.MouseClick(MouseButton.RIGHT) // 参数：B 键映射为鼠标右键点击动作
                ) // 结束 KeyMapping 构造
            }, // 结束 apply 作用域
            layers = listOf( // 命名参数：操作层列表，语法：listOf() 构造列表
                OperationLayer("Layer1").apply { // 语法：.apply{} 作用域函数；构造操作层 Layer1
                    buttonMappings[ControllerButton.A] = KeyMapping( // 语法：map[key]=value 键值赋值；给 A 按钮设置映射
                        MappedAction.KeyboardKey(30),  // KEYCODE_B = 30 // 参数：B 键键盘按键动作，键码 30
                        listOf(7, 8)  // 子命令 // 参数：语法：listOf() 子命令列表，含键码 7 和 8
                    ) // 结束 KeyMapping 构造
                } // 结束 apply 作用域
            ), // 结束 listOf 参数
            globalSettings = GlobalSettings(deadzone = 0.1f, lookSensitivity = 2.0f, cursorSpeed = 1.5f) // 参数：全局设置，语法：命名参数，自定义死区/灵敏度/光标速度
        ) // 结束 profile 调用

        val json = ControllerConfig.toJson(original, 2) // 语法：val 声明只读变量；把原始配置序列化为 JSON 字符串，版本号=2
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；把 JSON 反序列化为配置对象

        assertEquals(original.layers.size, parsed.layers.size) // 断言往返后操作层数量一致
        assertEquals(original.commonLayer.name, parsed.commonLayer.name) // 断言往返后公共层名称一致
        assertEquals(original.globalSettings.deadzone, parsed.globalSettings.deadzone, 0.001f) // 断言往返后死区一致（delta=0.001f 浮点误差允许）
        assertEquals(original.globalSettings.lookSensitivity, parsed.globalSettings.lookSensitivity, 0.001f) // 断言往返后视角灵敏度一致（delta=0.001f）
    } // 结束「往返测试 - 简单配置」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `往返测试 - 键盘按键带子命令`() { // 测试用例：键盘按键带子命令的往返测试
        val original = profile( // 语法：val 声明只读变量；调用辅助函数构造原始配置
            commonLayer = OperationLayer("Common").apply { // 语法：.apply{} 作用域函数；构造公共层
                buttonMappings[ControllerButton.X] = KeyMapping( // 语法：map[key]=value 键值赋值；给 X 按钮设置映射
                    MappedAction.KeyboardKey(57),  // KEYCODE_ALT_LEFT = 57 // 参数：Alt 左键键盘动作，键码 57
                    listOf(7, 8, 9)  // 3个子命令 // 参数：语法：listOf() 3 个子命令，键码 7/8/9
                ) // 结束 KeyMapping 构造
            }, // 结束 apply 作用域
            layers = emptyList() // 参数：语法：emptyList() 空列表，不设置操作层
        ) // 结束 profile 调用

        val json = ControllerConfig.toJson(original, 0) // 语法：val 声明只读变量；序列化为 JSON，版本号=0
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        val mapping = parsed.commonLayer.getMapping(ControllerButton.X) // 语法：val 声明只读变量；查询 X 按钮的映射
        assertNotNull(mapping) // 断言 X 按钮映射非空
        val action = mapping!!.action as MappedAction.KeyboardKey // 语法：!! 非空断言 + as 强转；取出键盘按键动作
        assertEquals(57, action.keyCode) // 断言动作键码为 57
        assertEquals(3, mapping.subCommands.size) // 断言子命令数量为 3
    } // 结束「往返测试 - 键盘按键带子命令」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `往返测试 - 切换层动作`() { // 测试用例：切换层动作的往返测试
        val original = profile( // 语法：val 声明只读变量；调用辅助函数构造原始配置
            commonLayer = OperationLayer("Common").apply { // 语法：.apply{} 作用域函数；构造公共层
                buttonMappings[ControllerButton.LEFT_SHOULDER] = KeyMapping( // 语法：map[key]=value 键值赋值；给左肩键设置映射
                    MappedAction.SwitchLayer("Layer5") // 参数：切换层动作，目标层 Layer5
                ) // 结束 KeyMapping 构造
            }, // 结束 apply 作用域
            layers = emptyList() // 参数：语法：emptyList() 空列表
        ) // 结束 profile 调用

        val json = ControllerConfig.toJson(original, 0) // 语法：val 声明只读变量；序列化为 JSON，版本号=0
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        val mapping = parsed.commonLayer.getMapping(ControllerButton.LEFT_SHOULDER) // 语法：val 声明只读变量；查询左肩键的映射
        assertNotNull(mapping) // 断言映射非空
        val action = mapping!!.action as MappedAction.SwitchLayer // 语法：!! 非空断言 + as 强转；取出切换层动作
        assertEquals("Layer5", action.layerName) // 断言切换目标层名为 "Layer5"
    } // 结束「往返测试 - 切换层动作」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `往返测试 - 视角控制动作`() { // 测试用例：视角控制动作的往返测试
        val original = profile( // 语法：val 声明只读变量；调用辅助函数构造原始配置
            commonLayer = OperationLayer("Common").apply { // 语法：.apply{} 作用域函数；构造公共层
                buttonMappings[ControllerButton.RIGHT_STICK_CLICK] = KeyMapping( // 语法：map[key]=value 键值赋值；给右摇杆按下设置映射
                    MappedAction.LookAround // 参数：视角环绕动作（LookAround）
                ) // 结束 KeyMapping 构造
            }, // 结束 apply 作用域
            layers = emptyList() // 参数：语法：emptyList() 空列表
        ) // 结束 profile 调用

        val json = ControllerConfig.toJson(original, 0) // 语法：val 声明只读变量；序列化为 JSON，版本号=0
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        val mapping = parsed.commonLayer.getMapping(ControllerButton.RIGHT_STICK_CLICK) // 语法：val 声明只读变量；查询右摇杆按下的映射
        assertNotNull(mapping) // 断言映射非空
        assertTrue(mapping!!.action is MappedAction.LookAround) // 断言反序列化后动作类型仍为 LookAround
    } // 结束「往返测试 - 视角控制动作」测试方法

    // ===== 默认配置序列化测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `默认配置序列化`() { // 测试用例：默认配置序列化后数据完整
        val original = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认配置档案

        val json = ControllerConfig.toJson(original, 2) // 语法：val 声明只读变量；序列化为 JSON，版本号=2
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        assertEquals(original.layers.size, parsed.layers.size) // 断言往返后操作层数量一致
        assertEquals("Common", parsed.commonLayer.name) // 断言公共层名为 "Common"
        assertEquals(ControllerProfile.DEFAULT_ACTION_SET_NAME, parsed.activeActionSetName) // 断言当前操作集名为默认名
        assertEquals(1, parsed.actionSets.size) // 断言操作集数量为 1
        // 验证所有10个层都在
        for (i in 1..10) { // 语法：for 循环，遍历 1 到 10
            assertNotNull(parsed.findLayer("Layer$i")) // 语法：字符串模板 $i 拼接层名；断言每个 Layer1~Layer10 都存在
        } // 结束 for 循环
    } // 结束「默认配置序列化」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `默认配置切入按键保留`() { // 测试用例：默认配置的切入按键应被保留
        val original = ControllerProfile.createDefault() // 语法：val 声明只读变量；构造默认配置档案

        val json = ControllerConfig.toJson(original, 0) // 语法：val 声明只读变量；序列化为 JSON，版本号=0
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        // 切入按键存储在公共层的 SwitchLayer 映射中，序列化后应保留
        fun switchInLayer(button: ControllerButton): String? = // 语法：fun 声明局部函数；返回该按钮切入的层名（可能为 null）
            (parsed.commonLayer.getMapping(button)?.action as? MappedAction.SwitchLayer)?.layerName // 语法：?. 空安全调用 + as? 安全转型；取切入层名

        assertEquals("Layer1", switchInLayer(ControllerButton.DPAD_UP)) // 断言方向上键仍切入 Layer1
        assertEquals("Layer10", switchInLayer(ControllerButton.RIGHT_TRIGGER_CLICK)) // 断言右扳机点击仍切入 Layer10
    } // 结束「默认配置切入按键保留」测试方法

    // ===== 操作集测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `多操作集往返`() { // 测试用例：多个操作集的往返测试
        // 两个操作集：默认 + 治疗
        val original = ControllerProfile( // 语法：val 声明只读变量；直接构造多操作集的配置档案
            actionSets = listOf( // 命名参数：操作集列表，语法：listOf() 构造列表
                ActionSet( // 构造第一个操作集
                    name = "默认", // 命名参数：操作集名「默认」
                    commonLayer = OperationLayer("Common").apply { // 命名参数：公共层，语法：.apply{} 作用域函数
                        // 语法：map[key]=value 键值赋值；A 键映射为键盘按键，键码 29
                        buttonMappings[ControllerButton.A] = KeyMapping(MappedAction.KeyboardKey(29))
                    }, // 结束 apply 作用域
                    layers = listOf(OperationLayer("Layer1")) // 命名参数：操作层列表，含一个 Layer1
                ), // 结束 ActionSet 构造
                ActionSet( // 构造第二个操作集
                    name = "治疗", // 命名参数：操作集名「治疗」
                    commonLayer = OperationLayer("Common").apply { // 命名参数：公共层，语法：.apply{} 作用域函数
                        // 语法：map[key]=value 键值赋值；A 键映射为键盘按键，键码 30
                        buttonMappings[ControllerButton.A] = KeyMapping(MappedAction.KeyboardKey(30))
                        // 语法：map[key]=value 键值赋值；B 键映射为鼠标左键点击
                        buttonMappings[ControllerButton.B] = KeyMapping(MappedAction.MouseClick(MouseButton.LEFT))
                    }, // 结束 apply 作用域
                    layers = listOf(OperationLayer("Layer1"), OperationLayer("Layer2")) // 命名参数：操作层列表，含 Layer1 和 Layer2
                ) // 结束 ActionSet 构造
            ), // 结束 listOf 参数
            activeActionSetName = "治疗" // 命名参数：当前激活的操作集名为「治疗」
        ) // 结束 ControllerProfile 构造

        val json = ControllerConfig.toJson(original, 2) // 语法：val 声明只读变量；序列化为 JSON，版本号=2
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        assertEquals(2, parsed.actionSets.size) // 断言操作集数量为 2
        // 当前操作集名保留
        assertEquals("治疗", parsed.activeActionSetName) // 断言当前操作集名保留为「治疗」
        assertEquals("治疗", parsed.activeActionSet.name) // 断言当前操作集对象名为「治疗」
        // 每个操作集内层独立
        assertEquals(1, parsed.findActionSet("默认")!!.layers.size) // 断言「默认」操作集有 1 个操作层
        assertEquals(2, parsed.findActionSet("治疗")!!.layers.size) // 断言「治疗」操作集有 2 个操作层
        // 当前操作集的按键映射来自「治疗」
        // 语法：?. 空安全调用 + as 强转；断言「治疗」操作集 A 键映射键码为 30
        assertEquals(30, (parsed.commonLayer.getMapping(ControllerButton.A)?.action as MappedAction.KeyboardKey).keyCode)
        // 切换到「默认」后映射不同
        val defaultParsed = parsed.copy(activeActionSetName = "默认") // 语法：data class 的 copy() 方法；复制对象并修改当前操作集名为「默认」
        // 语法：?. 空安全调用 + as 强转；断言切换到「默认」后 A 键映射键码为 29
        assertEquals(29, (defaultParsed.commonLayer.getMapping(ControllerButton.A)?.action as MappedAction.KeyboardKey).keyCode)
    } // 结束「多操作集往返」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `当前操作集名不存在时回退到第一个`() { // 测试用例：当前操作集名不存在时应回退到第一个
        val original = ControllerProfile( // 语法：val 声明只读变量；构造配置档案
            actionSets = listOf( // 命名参数：操作集列表，语法：listOf() 构造列表
                // 语法：命名参数；构造「默认」操作集，空层
                ActionSet(name = "默认", commonLayer = OperationLayer("Common"), layers = emptyList()),
                // 构造「治疗」操作集，空层
                ActionSet(name = "治疗", commonLayer = OperationLayer("Common"), layers = emptyList())
            ), // 结束 listOf 参数
            activeActionSetName = "不存在的操作集" // 命名参数：当前操作集名指向不存在的名称
        ) // 结束 ControllerProfile 构造

        val json = ControllerConfig.toJson(original, 0) // 语法：val 声明只读变量；序列化为 JSON，版本号=0
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        // activeActionSet 回退到第一个
        assertEquals("默认", parsed.activeActionSet.name) // 断言当前操作集回退为「默认」
    } // 结束「当前操作集名不存在时回退到第一个」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `version2 旧格式自动迁移为默认操作集`() { // 测试用例：v2 旧格式应自动迁移为默认操作集
        // 旧格式：顶层 commonLayer/layers，无 actionSets
        // 语法：val 声明只读变量；三引号原始字符串，用于写多行 JSON
        val json = """
        {
            "version": 2,
            "commonLayer": {
                "name": "Common",
                "buttonMappings": {
                    "A": {"action": {"type": "keyboard", "keyCode": 29}, "subCommands": []}
                }
            },
            "layers": [
                {"name": "Layer1", "buttonMappings": {}}
            ]
        }
        """.trimIndent() // 语法：trimIndent() 去掉字符串公共缩进
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        assertEquals(1, parsed.actionSets.size) // 断言迁移后操作集数量为 1
        assertEquals(ControllerProfile.DEFAULT_ACTION_SET_NAME, parsed.activeActionSetName) // 断言当前操作集名为默认名
        // 迁移后数据完整
        assertEquals(1, parsed.layers.size) // 断言迁移后操作层数量为 1
        assertNotNull(parsed.commonLayer.getMapping(ControllerButton.A)) // 断言迁移后公共层 A 按钮映射仍存在
    } // 结束「version2 旧格式自动迁移为默认操作集」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `actionSets 为空时回退默认操作集`() { // 测试用例：actionSets 为空时应回退到默认操作集
        val json = """{"version":3,"actionSets":[],"activeActionSet":"默认"}""" // 语法：val 声明只读变量；构造 v3 空操作集列表的 JSON
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        assertEquals(1, parsed.actionSets.size) // 断言回退后操作集数量为 1
        assertEquals(ControllerProfile.DEFAULT_ACTION_SET_NAME, parsed.actionSets.first().name) // 断言第一个操作集名为默认名
        // 默认操作集含 10 个操作层
        for (i in 1..10) { // 语法：for 循环，遍历 1 到 10
            assertNotNull(parsed.findLayer("Layer$i")) // 语法：字符串模板 $i 拼接层名；断言每个 Layer1~Layer10 都存在
        } // 结束 for 循环
    } // 结束「actionSets 为空时回退默认操作集」测试方法

    // ===== 全局设置测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `全局设置往返`() { // 测试用例：全局设置的往返测试
        val original = profile( // 语法：val 声明只读变量；调用辅助函数构造原始配置
            commonLayer = OperationLayer("Common"), // 命名参数：公共层
            layers = emptyList(), // 命名参数：语法：emptyList() 空操作层列表
            globalSettings = GlobalSettings(deadzone = 0.25f, lookSensitivity = 3.5f, cursorSpeed = 2.0f) // 命名参数：语法：命名参数，自定义全局设置
        ) // 结束 profile 调用

        val json = ControllerConfig.toJson(original, 0) // 语法：val 声明只读变量；序列化为 JSON，版本号=0
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        assertEquals(0.25f, parsed.globalSettings.deadzone, 0.001f) // 断言往返后死区=0.25f（delta=0.001f）
        assertEquals(3.5f, parsed.globalSettings.lookSensitivity, 0.001f) // 断言往返后视角灵敏度=3.5f（delta=0.001f）
        assertEquals(2.0f, parsed.globalSettings.cursorSpeed, 0.001f) // 断言往返后光标速度=2.0f（delta=0.001f）
    } // 结束「全局设置往返」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `缺失全局设置使用默认值`() { // 测试用例：缺失全局设置时应使用默认值
        val json = """{"version":2,"commonLayer":{"name":"Common","buttonMappings":{}},"layers":[]}""" // 语法：val 声明只读变量；构造无全局设置的 v2 JSON
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        assertEquals(0.15f, parsed.globalSettings.deadzone, 0.001f) // 断言缺失时死区用默认值 0.15f
        assertEquals(0.5f, parsed.globalSettings.lookSensitivity, 0.001f) // 断言缺失时视角灵敏度用默认值 0.5f
        assertEquals(1.0f, parsed.globalSettings.cursorSpeed, 0.001f) // 断言缺失时光标速度用默认值 1.0f
    } // 结束「缺失全局设置使用默认值」测试方法

    // ===== 错误处理 =====

    @Test(expected = IllegalArgumentException::class) // 语法：@Test(expected=...) 断言该方法应抛出 IllegalArgumentException 异常
    fun `错误版本号抛异常`() { // 测试用例：非法版本号应抛异常
        val json = """{"version":1,"commonLayer":{"name":"Common"}}""" // 语法：val 声明只读变量；构造版本号=1 的旧版 JSON
        ControllerConfig.fromJson(json) // 反序列化非法版本号，预期抛出异常
    } // 结束「错误版本号抛异常」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `空层列表可解析`() { // 测试用例：空层列表应可正常解析
        val json = """{"version":2,"commonLayer":{"name":"Common","buttonMappings":{}},"layers":[]}""" // 语法：val 声明只读变量；构造层列表为空的 v2 JSON
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        assertEquals("Common", parsed.commonLayer.name) // 断言公共层名为 "Common"
        assertTrue(parsed.layers.isEmpty()) // 断言操作层列表为空
    } // 结束「空层列表可解析」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `未知按钮名跳过`() { // 测试用例：未知按钮名应被跳过
        // 语法：val 声明只读变量；三引号原始字符串，用于写多行 JSON
        val json = """
        {
            "version": 2,
            "commonLayer": {
                "name": "Common",
                "buttonMappings": {
                    "UNKNOWN_BUTTON": {"action": {"type": "keyboard", "keyCode": 29}, "subCommands": []},
                    "A": {"action": {"type": "keyboard", "keyCode": 30}, "subCommands": []}
                }
            },
            "layers": []
        }
        """.trimIndent() // 语法：trimIndent() 去掉字符串公共缩进
        val parsed = ControllerConfig.fromJson(json) // 语法：val 声明只读变量；反序列化 JSON

        assertNull(parsed.commonLayer.getMapping(ControllerButton.B))  // UNKNOWN_BUTTON 被跳过 // 断言 B 按钮无映射（UNKNOWN_BUTTON 被跳过）
        assertNotNull(parsed.commonLayer.getMapping(ControllerButton.A)) // 断言 A 按钮映射存在
    } // 结束「未知按钮名跳过」测试方法
} // 结束 ControllerConfigTest 测试类
