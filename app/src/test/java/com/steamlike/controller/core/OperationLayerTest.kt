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
        assertNull(layer.triggerButton)
        assertTrue(layer.buttonMappings.isEmpty())
    }

    @Test
    fun `创建带触发键的层`() {
        val layer = OperationLayer("Layer1", ControllerButton.DPAD_UP)
        assertEquals("Layer1", layer.name)
        assertEquals(ControllerButton.DPAD_UP, layer.triggerButton)
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
    fun `createDefault触发键正确分配`() {
        val profile = ControllerProfile.createDefault()
        assertEquals(ControllerButton.DPAD_UP, profile.layers[0].triggerButton)
        assertEquals(ControllerButton.DPAD_DOWN, profile.layers[1].triggerButton)
        assertEquals(ControllerButton.DPAD_LEFT, profile.layers[2].triggerButton)
        assertEquals(ControllerButton.DPAD_RIGHT, profile.layers[3].triggerButton)
        assertEquals(ControllerButton.LEFT_SHOULDER, profile.layers[4].triggerButton)
        assertEquals(ControllerButton.RIGHT_SHOULDER, profile.layers[5].triggerButton)
        assertEquals(ControllerButton.LEFT_STICK_CLICK, profile.layers[6].triggerButton)
        // Layer8 触发键为 Touchpad（R3 保留为 LookAround 视角控制，不作为层切换键）
        assertEquals(ControllerButton.TOUCHPAD_CLICK, profile.layers[7].triggerButton)
        assertEquals(ControllerButton.LEFT_TRIGGER_CLICK, profile.layers[8].triggerButton)
        assertEquals(ControllerButton.RIGHT_TRIGGER_CLICK, profile.layers[9].triggerButton)
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
    fun `findLayerByTrigger按触发键查找`() {
        val profile = ControllerProfile.createDefault()
        val layer = profile.findLayerByTrigger(ControllerButton.DPAD_UP)
        assertNotNull(layer)
        assertEquals("Layer1", layer!!.name)
    }

    @Test
    fun `findLayerByTrigger公共层返回null`() {
        val profile = ControllerProfile.createDefault()
        // 公共层没有触发键，不会被 findLayerByTrigger 返回
        val layer = profile.findLayerByTrigger(ControllerButton.GUIDE)
        assertNull(layer)
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

        val layer1 = OperationLayer("Layer1", ControllerButton.DPAD_UP)
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

        val layer1 = OperationLayer("Layer1", ControllerButton.DPAD_UP)
        // Layer1 没有A的映射

        // 模拟查询: 激活Layer1时查A，Layer1没有则回退Common（公共层必有映射，结果非空）
        val mapping = (layer1.getMapping(ControllerButton.A) ?: common.getMapping(ControllerButton.A))!!
        assertEquals(KC_A, (mapping.action as MappedAction.KeyboardKey).keyCode)
    }

    @Test
    fun `层回退查询 - 都没映射返回null`() {
        val common = OperationLayer("Common")
        val layer1 = OperationLayer("Layer1", ControllerButton.DPAD_UP)

        val mapping = layer1.getMapping(ControllerButton.A) ?: common.getMapping(ControllerButton.A)
        assertNull(mapping)
    }
}
