package com.steamlike.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vector2 向量数学测试
 *
 * 测试内容:
 * - magnitude (向量长度)
 * - normalized (归一化)
 * - withDeadzone (死区处理)
 * - 边界情况 (零向量、极大值、负值)
 */
class Vector2Test {

    // ===== magnitude 测试 =====

    @Test
    fun `零向量的magnitude为0`() {
        assertEquals(0f, Vector2.ZERO.magnitude, 0.001f)
    }

    @Test
    fun `单位向量的magnitude为1`() {
        assertEquals(1f, Vector2(1f, 0f).magnitude, 0.001f)
        assertEquals(1f, Vector2(0f, 1f).magnitude, 0.001f)
        assertEquals(1f, Vector2(-1f, 0f).magnitude, 0.001f)
    }

    @Test
    fun `对角向量的magnitude为sqrt2`() {
        val expected = kotlin.math.sqrt(2f)
        assertEquals(expected, Vector2(1f, 1f).magnitude, 0.001f)
        assertEquals(expected, Vector2(-1f, -1f).magnitude, 0.001f)
    }

    @Test
    fun `magnitude不超过sqrt2`() {
        // 摇杆最大值为 (1,1)，magnitude = sqrt(2) ≈ 1.414
        val v = Vector2(1f, 1f)
        assertTrue(v.magnitude <= kotlin.math.sqrt(2f))
    }

    // ===== normalized 测试 =====

    @Test
    fun `归一化后magnitude为1`() {
        val v = Vector2(3f, 4f)  // magnitude = 5
        val normalized = v.normalized()
        assertEquals(1f, normalized.magnitude, 0.001f)
    }

    @Test
    fun `归一化保持方向`() {
        val v = Vector2(1f, 0f)
        val normalized = v.normalized()
        assertEquals(1f, normalized.x, 0.001f)
        assertEquals(0f, normalized.y, 0.001f)
    }

    @Test
    fun `归一化负方向`() {
        val v = Vector2(-1f, 0f)
        val normalized = v.normalized()
        assertEquals(-1f, normalized.x, 0.001f)
        assertEquals(0f, normalized.y, 0.001f)
    }

    @Test
    fun `零向量归一化返回零向量`() {
        val normalized = Vector2.ZERO.normalized()
        assertEquals(0f, normalized.x, 0.001f)
        assertEquals(0f, normalized.y, 0.001f)
    }

    // ===== withDeadzone 测试 =====

    @Test
    fun `死区内输入归零`() {
        val v = Vector2(0.05f, 0.03f)  // magnitude ≈ 0.058
        val result = v.withDeadzone(0.15f)
        assertEquals(0f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `死区外输入保留`() {
        val v = Vector2(1f, 0f)  // magnitude = 1
        val result = v.withDeadzone(0.15f)
        // (1 - 0.15) / (1 - 0.15) = 1.0
        assertEquals(1f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `死区边界外输入被缩放`() {
        // magnitude = 0.5, deadzone = 0.15
        // scale = (0.5 - 0.15) / (1 - 0.15) = 0.35 / 0.85 ≈ 0.4118
        val v = Vector2(0.5f, 0f)
        val result = v.withDeadzone(0.15f)
        val expectedScale = (0.5f - 0.15f) / (1f - 0.15f)
        assertEquals(expectedScale, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `零死区不改变输入`() {
        val v = Vector2(0.5f, 0.3f)
        val result = v.withDeadzone(0f)
        // 死区为0时，scale = mag / 1 = mag，但归一化后乘以mag，所以等于原值
        assertEquals(v.x, result.x, 0.01f)
        assertEquals(v.y, result.y, 0.01f)
    }

    @Test
    fun `死区为1时所有非满幅输入归零`() {
        val v = Vector2(0.5f, 0.5f)  // magnitude ≈ 0.707
        val result = v.withDeadzone(1f)
        assertEquals(0f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `死区恰好等于magnitude时归零`() {
        val v = Vector2(0.15f, 0f)  // magnitude = 0.15
        val result = v.withDeadzone(0.15f)
        // mag < deadzone → 归零
        assertEquals(0f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }
}
