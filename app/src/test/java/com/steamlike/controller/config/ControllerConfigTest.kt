package com.steamlike.controller.config

import com.steamlike.controller.core.ActionSet
import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerProfile
import com.steamlike.controller.core.GlobalSettings
import com.steamlike.controller.core.KeyMapping
import com.steamlike.controller.core.MappedAction
import com.steamlike.controller.core.MouseButton
import com.steamlike.controller.core.OperationLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
class ControllerConfigTest {

    // ===== 辅助构造 =====

    /**
     * 构造单操作集配置（模拟旧 commonLayer/layers 的易用性）
     */
    private fun profile(
        commonLayer: OperationLayer,
        layers: List<OperationLayer>,
        globalSettings: GlobalSettings = GlobalSettings(),
        actionSetName: String = ControllerProfile.DEFAULT_ACTION_SET_NAME
    ): ControllerProfile = ControllerProfile(
        actionSets = listOf(ActionSet(name = actionSetName, commonLayer = commonLayer, layers = layers)),
        globalSettings = globalSettings
    )

    // ===== 往返测试 =====

    @Test
    fun `往返测试 - 简单配置`() {
        val original = profile(
            commonLayer = OperationLayer("Common").apply {
                buttonMappings[ControllerButton.A] = KeyMapping(
                    MappedAction.KeyboardKey(29)  // KEYCODE_A = 29
                )
                buttonMappings[ControllerButton.B] = KeyMapping(
                    MappedAction.MouseClick(MouseButton.RIGHT)
                )
            },
            layers = listOf(
                OperationLayer("Layer1").apply {
                    buttonMappings[ControllerButton.A] = KeyMapping(
                        MappedAction.KeyboardKey(30),  // KEYCODE_B = 30
                        listOf(7, 8)  // 子命令
                    )
                }
            ),
            globalSettings = GlobalSettings(deadzone = 0.1f, lookSensitivity = 2.0f, cursorSpeed = 1.5f)
        )

        val json = ControllerConfig.toJson(original, 2)
        val parsed = ControllerConfig.fromJson(json)

        assertEquals(original.layers.size, parsed.layers.size)
        assertEquals(original.commonLayer.name, parsed.commonLayer.name)
        assertEquals(original.globalSettings.deadzone, parsed.globalSettings.deadzone, 0.001f)
        assertEquals(original.globalSettings.lookSensitivity, parsed.globalSettings.lookSensitivity, 0.001f)
    }

    @Test
    fun `往返测试 - 键盘按键带子命令`() {
        val original = profile(
            commonLayer = OperationLayer("Common").apply {
                buttonMappings[ControllerButton.X] = KeyMapping(
                    MappedAction.KeyboardKey(57),  // KEYCODE_ALT_LEFT = 57
                    listOf(7, 8, 9)  // 3个子命令
                )
            },
            layers = emptyList()
        )

        val json = ControllerConfig.toJson(original, 0)
        val parsed = ControllerConfig.fromJson(json)

        val mapping = parsed.commonLayer.getMapping(ControllerButton.X)
        assertNotNull(mapping)
        val action = mapping!!.action as MappedAction.KeyboardKey
        assertEquals(57, action.keyCode)
        assertEquals(3, mapping.subCommands.size)
    }

    @Test
    fun `往返测试 - 切换层动作`() {
        val original = profile(
            commonLayer = OperationLayer("Common").apply {
                buttonMappings[ControllerButton.LEFT_SHOULDER] = KeyMapping(
                    MappedAction.SwitchLayer("Layer5")
                )
            },
            layers = emptyList()
        )

        val json = ControllerConfig.toJson(original, 0)
        val parsed = ControllerConfig.fromJson(json)

        val mapping = parsed.commonLayer.getMapping(ControllerButton.LEFT_SHOULDER)
        assertNotNull(mapping)
        val action = mapping!!.action as MappedAction.SwitchLayer
        assertEquals("Layer5", action.layerName)
    }

    @Test
    fun `往返测试 - 视角控制动作`() {
        val original = profile(
            commonLayer = OperationLayer("Common").apply {
                buttonMappings[ControllerButton.RIGHT_STICK_CLICK] = KeyMapping(
                    MappedAction.LookAround
                )
            },
            layers = emptyList()
        )

        val json = ControllerConfig.toJson(original, 0)
        val parsed = ControllerConfig.fromJson(json)

        val mapping = parsed.commonLayer.getMapping(ControllerButton.RIGHT_STICK_CLICK)
        assertNotNull(mapping)
        assertTrue(mapping!!.action is MappedAction.LookAround)
    }

    // ===== 默认配置序列化测试 =====

    @Test
    fun `默认配置序列化`() {
        val original = ControllerProfile.createDefault()

        val json = ControllerConfig.toJson(original, 2)
        val parsed = ControllerConfig.fromJson(json)

        assertEquals(original.layers.size, parsed.layers.size)
        assertEquals("Common", parsed.commonLayer.name)
        assertEquals(ControllerProfile.DEFAULT_ACTION_SET_NAME, parsed.activeActionSetName)
        assertEquals(1, parsed.actionSets.size)
        // 验证所有10个层都在
        for (i in 1..10) {
            assertNotNull(parsed.findLayer("Layer$i"))
        }
    }

    @Test
    fun `默认配置切入按键保留`() {
        val original = ControllerProfile.createDefault()

        val json = ControllerConfig.toJson(original, 0)
        val parsed = ControllerConfig.fromJson(json)

        // 切入按键存储在公共层的 SwitchLayer 映射中，序列化后应保留
        fun switchInLayer(button: ControllerButton): String? =
            (parsed.commonLayer.getMapping(button)?.action as? MappedAction.SwitchLayer)?.layerName

        assertEquals("Layer1", switchInLayer(ControllerButton.DPAD_UP))
        assertEquals("Layer10", switchInLayer(ControllerButton.RIGHT_TRIGGER_CLICK))
    }

    // ===== 操作集测试 =====

    @Test
    fun `多操作集往返`() {
        // 两个操作集：默认 + 治疗
        val original = ControllerProfile(
            actionSets = listOf(
                ActionSet(
                    name = "默认",
                    commonLayer = OperationLayer("Common").apply {
                        buttonMappings[ControllerButton.A] = KeyMapping(MappedAction.KeyboardKey(29))
                    },
                    layers = listOf(OperationLayer("Layer1"))
                ),
                ActionSet(
                    name = "治疗",
                    commonLayer = OperationLayer("Common").apply {
                        buttonMappings[ControllerButton.A] = KeyMapping(MappedAction.KeyboardKey(30))
                        buttonMappings[ControllerButton.B] = KeyMapping(MappedAction.MouseClick(MouseButton.LEFT))
                    },
                    layers = listOf(OperationLayer("Layer1"), OperationLayer("Layer2"))
                )
            ),
            activeActionSetName = "治疗"
        )

        val json = ControllerConfig.toJson(original, 2)
        val parsed = ControllerConfig.fromJson(json)

        assertEquals(2, parsed.actionSets.size)
        // 当前操作集名保留
        assertEquals("治疗", parsed.activeActionSetName)
        assertEquals("治疗", parsed.activeActionSet.name)
        // 每个操作集内层独立
        assertEquals(1, parsed.findActionSet("默认")!!.layers.size)
        assertEquals(2, parsed.findActionSet("治疗")!!.layers.size)
        // 当前操作集的按键映射来自「治疗」
        assertEquals(30, (parsed.commonLayer.getMapping(ControllerButton.A)?.action as MappedAction.KeyboardKey).keyCode)
        // 切换到「默认」后映射不同
        val defaultParsed = parsed.copy(activeActionSetName = "默认")
        assertEquals(29, (defaultParsed.commonLayer.getMapping(ControllerButton.A)?.action as MappedAction.KeyboardKey).keyCode)
    }

    @Test
    fun `当前操作集名不存在时回退到第一个`() {
        val original = ControllerProfile(
            actionSets = listOf(
                ActionSet(name = "默认", commonLayer = OperationLayer("Common"), layers = emptyList()),
                ActionSet(name = "治疗", commonLayer = OperationLayer("Common"), layers = emptyList())
            ),
            activeActionSetName = "不存在的操作集"
        )

        val json = ControllerConfig.toJson(original, 0)
        val parsed = ControllerConfig.fromJson(json)

        // activeActionSet 回退到第一个
        assertEquals("默认", parsed.activeActionSet.name)
    }

    @Test
    fun `version2 旧格式自动迁移为默认操作集`() {
        // 旧格式：顶层 commonLayer/layers，无 actionSets
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
        """.trimIndent()
        val parsed = ControllerConfig.fromJson(json)

        assertEquals(1, parsed.actionSets.size)
        assertEquals(ControllerProfile.DEFAULT_ACTION_SET_NAME, parsed.activeActionSetName)
        // 迁移后数据完整
        assertEquals(1, parsed.layers.size)
        assertNotNull(parsed.commonLayer.getMapping(ControllerButton.A))
    }

    @Test
    fun `actionSets 为空时回退默认操作集`() {
        val json = """{"version":3,"actionSets":[],"activeActionSet":"默认"}"""
        val parsed = ControllerConfig.fromJson(json)

        assertEquals(1, parsed.actionSets.size)
        assertEquals(ControllerProfile.DEFAULT_ACTION_SET_NAME, parsed.actionSets.first().name)
        // 默认操作集含 10 个操作层
        for (i in 1..10) {
            assertNotNull(parsed.findLayer("Layer$i"))
        }
    }

    // ===== 全局设置测试 =====

    @Test
    fun `全局设置往返`() {
        val original = profile(
            commonLayer = OperationLayer("Common"),
            layers = emptyList(),
            globalSettings = GlobalSettings(deadzone = 0.25f, lookSensitivity = 3.5f, cursorSpeed = 2.0f)
        )

        val json = ControllerConfig.toJson(original, 0)
        val parsed = ControllerConfig.fromJson(json)

        assertEquals(0.25f, parsed.globalSettings.deadzone, 0.001f)
        assertEquals(3.5f, parsed.globalSettings.lookSensitivity, 0.001f)
        assertEquals(2.0f, parsed.globalSettings.cursorSpeed, 0.001f)
    }

    @Test
    fun `缺失全局设置使用默认值`() {
        val json = """{"version":2,"commonLayer":{"name":"Common","buttonMappings":{}},"layers":[]}"""
        val parsed = ControllerConfig.fromJson(json)

        assertEquals(0.15f, parsed.globalSettings.deadzone, 0.001f)
        assertEquals(0.5f, parsed.globalSettings.lookSensitivity, 0.001f)
        assertEquals(1.0f, parsed.globalSettings.cursorSpeed, 0.001f)
    }

    // ===== 错误处理 =====

    @Test(expected = IllegalArgumentException::class)
    fun `错误版本号抛异常`() {
        val json = """{"version":1,"commonLayer":{"name":"Common"}}"""
        ControllerConfig.fromJson(json)
    }

    @Test
    fun `空层列表可解析`() {
        val json = """{"version":2,"commonLayer":{"name":"Common","buttonMappings":{}},"layers":[]}"""
        val parsed = ControllerConfig.fromJson(json)

        assertEquals("Common", parsed.commonLayer.name)
        assertTrue(parsed.layers.isEmpty())
    }

    @Test
    fun `未知按钮名跳过`() {
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
        """.trimIndent()
        val parsed = ControllerConfig.fromJson(json)

        assertNull(parsed.commonLayer.getMapping(ControllerButton.B))  // UNKNOWN_BUTTON 被跳过
        assertNotNull(parsed.commonLayer.getMapping(ControllerButton.A))
    }
}
