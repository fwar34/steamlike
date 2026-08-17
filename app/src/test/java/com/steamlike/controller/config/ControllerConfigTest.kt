package com.steamlike.controller.config

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
 * - 错误处理
 */
class ControllerConfigTest {

    // ===== 往返测试 =====

    @Test
    fun `往返测试 - 简单配置`() {
        val original = ControllerProfile(
            commonLayer = OperationLayer("Common").apply {
                buttonMappings[ControllerButton.A] = KeyMapping(
                    MappedAction.KeyboardKey(29)  // KEYCODE_A = 29
                )
                buttonMappings[ControllerButton.B] = KeyMapping(
                    MappedAction.MouseClick(MouseButton.RIGHT)
                )
            },
            layers = listOf(
                OperationLayer("Layer1", ControllerButton.DPAD_UP).apply {
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
        val original = ControllerProfile(
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
        val original = ControllerProfile(
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
        val original = ControllerProfile(
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
        // 验证所有10个层都在
        for (i in 1..10) {
            assertNotNull(parsed.findLayer("Layer$i"))
        }
    }

    @Test
    fun `默认配置触发键保留`() {
        val original = ControllerProfile.createDefault()

        val json = ControllerConfig.toJson(original, 0)
        val parsed = ControllerConfig.fromJson(json)

        assertEquals(ControllerButton.DPAD_UP, parsed.layers[0].triggerButton)
        assertEquals(ControllerButton.RIGHT_TRIGGER_CLICK, parsed.layers[9].triggerButton)
    }

    // ===== 全局设置测试 =====

    @Test
    fun `全局设置往返`() {
        val original = ControllerProfile(
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

        assertEquals(0.0f, parsed.globalSettings.deadzone, 0.001f)
        assertEquals(1.0f, parsed.globalSettings.lookSensitivity, 0.001f)
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
