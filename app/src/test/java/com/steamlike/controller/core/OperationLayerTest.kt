package com.steamlike.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
class OperationLayerTest {

    // KeyCode 常量
    private val KC_A = 29
    private val KC_B = 30

    // ===== OperationLayer 测试 =====

    @Test
    fun `创建空层`() {
        val layer = OperationLayer("Test")
        assertEquals("Test", layer.name)
        assertTrue(layer.buttonMappings.isEmpty())
    }

    @Test
    fun `添加和查询映射`() {
        val layer = OperationLayer("Test")
        val mapping = KeyMapping(MappedAction.KeyboardKey(KC_A))
        layer.buttonMappings[ControllerButton.A] = mapping
        assertEquals(mapping, layer.getMapping(ControllerButton.A))
        assertNull(layer.getMapping(ControllerButton.B))
    }

    // ===== ControllerProfile 测试 =====

    @Test
    fun `createDefault创建10个层`() {
        val profile = ControllerProfile.createDefault()
        assertEquals(10, profile.layers.size)
        assertEquals("Common", profile.commonLayer.name)
    }

    @Test
    fun `createDefault公共层有默认映射`() {
        val profile = ControllerProfile.createDefault()
        assertNotNull(profile.commonLayer.getMapping(ControllerButton.A))
        assertNotNull(profile.commonLayer.getMapping(ControllerButton.B))
    }

    @Test
    fun `createDefault层名正确`() {
        val profile = ControllerProfile.createDefault()
        assertEquals("Layer1", profile.layers[0].name)
        assertEquals("Layer10", profile.layers[9].name)
    }

    @Test
    fun `createDefault公共层切入按键正确分配`() {
        val profile = ControllerProfile.createDefault()
        val common = profile.commonLayer
        fun switchInLayer(button: ControllerButton): String? =
            (common.getMapping(button)?.action as? MappedAction.SwitchLayer)?.layerName

        assertEquals("Layer1", switchInLayer(ControllerButton.DPAD_UP))
        assertEquals("Layer2", switchInLayer(ControllerButton.DPAD_DOWN))
        assertEquals("Layer3", switchInLayer(ControllerButton.DPAD_LEFT))
        assertEquals("Layer4", switchInLayer(ControllerButton.DPAD_RIGHT))
        assertEquals("Layer5", switchInLayer(ControllerButton.LEFT_SHOULDER))
        assertEquals("Layer6", switchInLayer(ControllerButton.RIGHT_SHOULDER))
        assertEquals("Layer7", switchInLayer(ControllerButton.LEFT_STICK_CLICK))
        // Layer8 切入键为 Touchpad（R3 保留为 LookAround 视角控制，不作为层切换键）
        assertEquals("Layer8", switchInLayer(ControllerButton.TOUCHPAD_CLICK))
        assertEquals("Layer9", switchInLayer(ControllerButton.LEFT_TRIGGER_CLICK))
        assertEquals("Layer10", switchInLayer(ControllerButton.RIGHT_TRIGGER_CLICK))
    }

    @Test
    fun `allLayers包含公共层和操作层`() {
        val profile = ControllerProfile.createDefault()
        assertEquals(11, profile.allLayers.size) // Common + 10 layers
        assertEquals("Common", profile.allLayers[0].name)
        assertEquals("Layer1", profile.allLayers[1].name)
    }

    @Test
    fun `findLayer按名称查找`() {
        val profile = ControllerProfile.createDefault()
        assertNotNull(profile.findLayer("Common"))
        assertNotNull(profile.findLayer("Layer1"))
        assertNotNull(profile.findLayer("Layer10"))
        assertNull(profile.findLayer("NotExist"))
    }

    @Test
    fun `findLayerBySwitchIn公共层按切入键查找`() {
        val profile = ControllerProfile.createDefault()
        // 公共层中查找指向 Layer1 的切入按键（SwitchLayer 映射）
        val switchInButton = profile.commonLayer.buttonMappings.entries
            .firstOrNull { (button, mapping) ->
                (mapping.action as? MappedAction.SwitchLayer)?.layerName == "Layer1"
            }
            ?.key
        assertEquals(ControllerButton.DPAD_UP, switchInButton)
    }

    @Test
    fun `findLayerBySwitchIn公共层无映射返回null`() {
        val profile = ControllerProfile.createDefault()
        // 公共层中无指向不存在层的切入按键
        val switchInButton = profile.commonLayer.buttonMappings.entries
            .firstOrNull { (button, mapping) ->
                (mapping.action as? MappedAction.SwitchLayer)?.layerName == "NotExist"
            }
            ?.key
        assertNull(switchInButton)
    }

    // ===== 全局设置测试 =====

    @Test
    fun `默认全局设置死区为015`() {
        val settings = GlobalSettings()
        assertEquals(0.15f, settings.deadzone, 0.001f)
    }

    @Test
    fun `默认全局设置灵敏度为05`() {
        val settings = GlobalSettings()
        assertEquals(0.5f, settings.lookSensitivity, 0.001f)
        assertEquals(1.0f, settings.cursorSpeed, 0.001f)
    }

    // ===== 层回退查询逻辑测试 =====

    @Test
    fun `层回退查询 - 操作层有映射时使用操作层`() {
        val common = OperationLayer("Common")
        common.buttonMappings[ControllerButton.A] =
            KeyMapping(MappedAction.KeyboardKey(KC_A))

        val layer1 = OperationLayer("Layer1")
        layer1.buttonMappings[ControllerButton.A] =
            KeyMapping(MappedAction.KeyboardKey(KC_B))

        // 模拟查询: 激活Layer1时查A（本测试中 Layer1 必有映射，结果非空）
        val mapping = (layer1.getMapping(ControllerButton.A) ?: common.getMapping(ControllerButton.A))!!
        assertEquals(KC_B, (mapping.action as MappedAction.KeyboardKey).keyCode)
    }

    @Test
    fun `层回退查询 - 操作层无映射时回退公共层`() {
        val common = OperationLayer("Common")
        common.buttonMappings[ControllerButton.A] =
            KeyMapping(MappedAction.KeyboardKey(KC_A))

        val layer1 = OperationLayer("Layer1")
        // Layer1 没有A的映射

        // 模拟查询: 激活Layer1时查A，Layer1没有则回退Common（公共层必有映射，结果非空）
        val mapping = (layer1.getMapping(ControllerButton.A) ?: common.getMapping(ControllerButton.A))!!
        assertEquals(KC_A, (mapping.action as MappedAction.KeyboardKey).keyCode)
    }

    @Test
    fun `层回退查询 - 都没映射返回null`() {
        val common = OperationLayer("Common")
        val layer1 = OperationLayer("Layer1")

        val mapping = layer1.getMapping(ControllerButton.A) ?: common.getMapping(ControllerButton.A)
        assertNull(mapping)
    }
}
