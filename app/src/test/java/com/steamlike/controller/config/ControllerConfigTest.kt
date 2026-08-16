package com.steamlike.controller.config

import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerStick
import com.steamlike.controller.core.ControllerTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ControllerConfig 配置文件序列化/反序列化测试
 *
 * 测试内容:
 * - JSON 序列化 (toJsonString)
 * - JSON 反序列化 (parseConfig)
 * - 往返测试 (序列化→反序列化→验证一致性)
 * - 枚举名解析 (parseButton/parseStick/parseTrigger)
 * - 边界情况 (空配置、缺失字段、无效枚举名)
 */
class ControllerConfigTest {

    // ===== 序列化测试 =====

    @Test
    fun `空配置序列化为有效JSON`() {
        val config = ControllerConfig()
        val json = config.toJsonString()

        // 解析回来验证
        val parsed = parseConfig(json)
        assertEquals(ControllerConfig.CURRENT_VERSION, parsed.version)
        assertTrue(parsed.name.isEmpty())
        assertTrue(parsed.layers.isEmpty())
    }

    @Test
    fun `完整配置序列化包含所有字段`() {
        val config = ControllerConfig(
            version = 1,
            name = "测试配置",
            description = "用于测试的配置文件",
            commonLayer = CommonLayerConfig(
                buttonBindings = mapOf("A" to "Jump", "B" to "Interact"),
                chordBindings = listOf(
                    ChordBindingConfig("A", "TargetEnemy", listOf("RIGHT_SHOULDER"))
                ),
                stickBindings = mapOf("LEFT_STICK" to "Move"),
                triggerBindings = mapOf("RIGHT_TRIGGER" to "Cast"),
                stickProperties = mapOf("Move" to StickPropertiesConfig(0.2f, 1.3f)),
                triggerProperties = mapOf("Cast" to TriggerPropertiesConfig(0.3f))
            ),
            layers = listOf(
                LayerConfig(
                    name = "Combat",
                    displayName = "战斗模式",
                    buttonBindingOverrides = mapOf("A" to "Slot5"),
                    stickOverrides = mapOf("Look" to StickPropertiesConfig(0.25f, 0.8f)),
                    triggerOverrides = emptyMap()
                )
            )
        )

        val json = config.toJsonString(2)

        // 验证关键字段存在
        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"测试配置\""))
        assertTrue(json.contains("\"commonLayer\""))
        assertTrue(json.contains("\"buttonBindings\""))
        assertTrue(json.contains("\"Jump\""))
        assertTrue(json.contains("\"chordBindings\""))
        assertTrue(json.contains("\"TargetEnemy\""))
        assertTrue(json.contains("\"layers\""))
        assertTrue(json.contains("\"Combat\""))
        assertTrue(json.contains("\"Slot5\""))
    }

    // ===== 反序列化测试 =====

    @Test
    fun `从JSON字符串解析完整配置`() {
        val jsonStr = """{
            "version": 1,
            "name": "测试",
            "description": "测试描述",
            "commonLayer": {
                "buttonBindings": {"A": "Jump", "B": "Interact"},
                "chordBindings": [
                    {"button": "A", "action": "TargetEnemy", "chord": ["RIGHT_SHOULDER"]}
                ],
                "stickBindings": {"LEFT_STICK": "Move"},
                "triggerBindings": {"RIGHT_TRIGGER": "Cast"},
                "stickProperties": {"Move": {"deadzone": 0.2, "responseCurve": 1.3}},
                "triggerProperties": {"Cast": {"pressThreshold": 0.3}}
            },
            "layers": [
                {
                    "name": "Combat",
                    "displayName": "战斗模式",
                    "buttonBindingOverrides": {"A": "Slot5"},
                    "stickOverrides": {"Look": {"deadzone": 0.25, "responseCurve": 0.8}},
                    "triggerOverrides": {}
                }
            ]
        }"""

        val config = parseConfig(jsonStr)

        assertEquals(1, config.version)
        assertEquals("测试", config.name)
        assertEquals("测试描述", config.description)
        assertEquals("Jump", config.commonLayer.buttonBindings["A"])
        assertEquals("Interact", config.commonLayer.buttonBindings["B"])
        assertEquals(1, config.commonLayer.chordBindings.size)
        assertEquals("TargetEnemy", config.commonLayer.chordBindings[0].action)
        assertEquals(listOf("RIGHT_SHOULDER"), config.commonLayer.chordBindings[0].chord)
        assertEquals("Move", config.commonLayer.stickBindings["LEFT_STICK"])
        assertEquals("Cast", config.commonLayer.triggerBindings["RIGHT_TRIGGER"])
        assertEquals(0.2f, config.commonLayer.stickProperties["Move"]!!.deadzone!!, 0.001f)
        assertEquals(1.3f, config.commonLayer.stickProperties["Move"]!!.responseCurve!!, 0.001f)
        assertEquals(0.3f, config.commonLayer.triggerProperties["Cast"]!!.pressThreshold!!, 0.001f)

        assertEquals(1, config.layers.size)
        val layer = config.layers[0]
        assertEquals("Combat", layer.name)
        assertEquals("战斗模式", layer.displayName)
        assertEquals("Slot5", layer.buttonBindingOverrides["A"])
        assertEquals(0.25f, layer.stickOverrides["Look"]!!.deadzone!!, 0.001f)
        assertEquals(0.8f, layer.stickOverrides["Look"]!!.responseCurve!!, 0.001f)
    }

    @Test
    fun `解析缺失可选字段的JSON`() {
        val jsonStr = """{"version":1,"name":"最小配置"}"""
        val config = parseConfig(jsonStr)

        assertEquals(1, config.version)
        assertEquals("最小配置", config.name)
        assertTrue(config.description.isEmpty())
        assertTrue(config.commonLayer.buttonBindings.isEmpty())
        assertTrue(config.layers.isEmpty())
    }

    @Test
    fun `解析缺失version字段时使用当前版本`() {
        val jsonStr = """{"name":"无版本号"}"""
        val config = parseConfig(jsonStr)
        assertEquals(ControllerConfig.CURRENT_VERSION, config.version)
    }

    @Test
    fun `解析null commonLayer时返回空配置`() {
        val jsonStr = """{"version":1,"name":"无公共层"}"""
        val config = parseConfig(jsonStr)
        assertTrue(config.commonLayer.buttonBindings.isEmpty())
        assertTrue(config.commonLayer.chordBindings.isEmpty())
    }

    @Test
    fun `解析空chord的组合键绑定`() {
        val jsonStr = """{
            "version":1,
            "name":"test",
            "commonLayer": {
                "chordBindings": [
                    {"button": "A", "action": "Jump"}
                ]
            }
        }"""

        val config = parseConfig(jsonStr)
        assertEquals(1, config.commonLayer.chordBindings.size)
        assertEquals("A", config.commonLayer.chordBindings[0].button)
        assertEquals("Jump", config.commonLayer.chordBindings[0].action)
        assertTrue(config.commonLayer.chordBindings[0].chord.isEmpty())
    }

    @Test
    fun `解析多键chord的组合键绑定`() {
        val jsonStr = """{
            "version":1,
            "name":"test",
            "commonLayer": {
                "chordBindings": [
                    {"button": "A", "action": "Potion", "chord": ["RIGHT_SHOULDER", "LEFT_TRIGGER_CLICK"]}
                ]
            }
        }"""

        val config = parseConfig(jsonStr)
        val cb = config.commonLayer.chordBindings[0]
        assertEquals(2, cb.chord.size)
        assertEquals("RIGHT_SHOULDER", cb.chord[0])
        assertEquals("LEFT_TRIGGER_CLICK", cb.chord[1])
    }

    @Test
    fun `解析摇杆属性缺失responseCurve时返回null`() {
        val jsonStr = """{
            "version":1,
            "name":"test",
            "commonLayer": {
                "stickProperties": {"Move": {"deadzone": 0.2}}
            }
        }"""

        val config = parseConfig(jsonStr)
        val props = config.commonLayer.stickProperties["Move"]!!
        assertEquals(0.2f, props.deadzone!!, 0.001f)
        assertNull(props.responseCurve)
    }

    @Test
    fun `解析层配置缺失displayName时使用name`() {
        val jsonStr = """{
            "version":1,
            "name":"test",
            "layers": [{"name": "Combat"}]
        }"""

        val config = parseConfig(jsonStr)
        val layer = config.layers[0]
        assertEquals("Combat", layer.name)
        assertEquals("Combat", layer.displayName)
    }

    // ===== 往返测试 (Round-trip) =====

    @Test
    fun `配置序列化后反序列化保持一致`() {
        val original = ControllerConfig(
            version = 1,
            name = "往返测试",
            description = "验证序列化+反序列化的一致性",
            commonLayer = CommonLayerConfig(
                buttonBindings = mapOf("A" to "Jump", "B" to "Interact", "X" to "Attack"),
                chordBindings = listOf(
                    ChordBindingConfig("A", "Jump"),
                    ChordBindingConfig("A", "Slot5", listOf("RIGHT_SHOULDER")),
                    ChordBindingConfig("DPAD_UP", "Slot9", listOf("RIGHT_STICK_CLICK"))
                ),
                stickBindings = mapOf("LEFT_STICK" to "Move", "RIGHT_STICK" to "Look"),
                triggerBindings = mapOf("LEFT_TRIGGER" to "Modifier", "RIGHT_TRIGGER" to "Cast"),
                stickProperties = mapOf(
                    "Move" to StickPropertiesConfig(0.2f, 1.3f),
                    "Look" to StickPropertiesConfig(0.15f, 1.0f)
                ),
                triggerProperties = mapOf(
                    "Cast" to TriggerPropertiesConfig(0.3f),
                    "Modifier" to TriggerPropertiesConfig(0.5f)
                )
            ),
            layers = listOf(
                LayerConfig("Combat", "战斗", mapOf("A" to "Slot5"), emptyMap(), emptyMap()),
                LayerConfig("Aim", "瞄准", emptyMap(),
                    mapOf("Look" to StickPropertiesConfig(0.25f, 0.5f)),
                    mapOf("Cast" to TriggerPropertiesConfig(0.6f))
                )
            )
        )

        val json = original.toJsonString(2)
        val parsed = parseConfig(json)

        assertEquals(original.version, parsed.version)
        assertEquals(original.name, parsed.name)
        assertEquals(original.description, parsed.description)
        assertEquals(original.commonLayer.buttonBindings, parsed.commonLayer.buttonBindings)
        assertEquals(original.commonLayer.stickBindings, parsed.commonLayer.stickBindings)
        assertEquals(original.commonLayer.triggerBindings, parsed.commonLayer.triggerBindings)
        assertEquals(original.commonLayer.chordBindings.size, parsed.commonLayer.chordBindings.size)
        assertEquals(original.layers.size, parsed.layers.size)
        assertEquals(original.layers[0].buttonBindingOverrides, parsed.layers[0].buttonBindingOverrides)
        assertEquals(original.layers[1].stickOverrides, parsed.layers[1].stickOverrides)
    }

    @Test
    fun `空配置往返测试`() {
        val original = ControllerConfig()
        val json = original.toJsonString()
        val parsed = parseConfig(json)
        assertEquals(original, parsed)
    }

    // ===== 枚举名解析测试 =====

    @Test
    fun `parseButton解析有效按钮名`() {
        assertEquals(ControllerButton.A, parseButton("A"))
        assertEquals(ControllerButton.B, parseButton("B"))
        assertEquals(ControllerButton.RIGHT_SHOULDER, parseButton("RIGHT_SHOULDER"))
        assertEquals(ControllerButton.DPAD_UP, parseButton("DPAD_UP"))
        assertEquals(ControllerButton.LEFT_STICK_CLICK, parseButton("LEFT_STICK_CLICK"))
    }

    @Test
    fun `parseButton无效名称返回null`() {
        assertNull(parseButton("Invalid"))
        assertNull(parseButton("a"))  // 大小写敏感
        assertNull(parseButton(""))
    }

    @Test
    fun `parseStick解析有效摇杆名`() {
        assertEquals(ControllerStick.LEFT_STICK, parseStick("LEFT_STICK"))
        assertEquals(ControllerStick.RIGHT_STICK, parseStick("RIGHT_STICK"))
        assertEquals(ControllerStick.DPAD_AS_STICK, parseStick("DPAD_AS_STICK"))
    }

    @Test
    fun `parseStick无效名称返回null`() {
        assertNull(parseStick("left_stick"))  // 大小写敏感
        assertNull(parseStick("Invalid"))
    }

    @Test
    fun `parseTrigger解析有效扳机名`() {
        assertEquals(ControllerTrigger.LEFT_TRIGGER, parseTrigger("LEFT_TRIGGER"))
        assertEquals(ControllerTrigger.RIGHT_TRIGGER, parseTrigger("RIGHT_TRIGGER"))
    }

    @Test
    fun `parseTrigger无效名称返回null`() {
        assertNull(parseTrigger("LT"))
        assertNull(parseTrigger("Invalid"))
    }

    // ===== 紧凑 vs 格式化输出 =====

    @Test
    fun `缩进为0时输出紧凑JSON`() {
        val config = ControllerConfig(name = "test")
        val json = config.toJsonString(0)
        // 紧凑模式不应包含换行
        assertTrue(!json.contains("\n"))
    }

    @Test
    fun `缩进为2时输出格式化JSON`() {
        val config = ControllerConfig(name = "test")
        val json = config.toJsonString(2)
        // 格式化模式应包含换行
        assertTrue(json.contains("\n"))
    }
}
