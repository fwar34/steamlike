package com.steamlike.controller.core // 包声明：控制器核心组件所在的包

import org.junit.Assert.assertEquals // 导入JUnit断言函数：断言相等
import org.junit.Assert.assertTrue // 导入JUnit断言函数：断言为真
import org.junit.Test // 导入JUnit测试注解 @Test

/**
 * Vector2 向量数学测试
 *
 * 测试内容:
 * - magnitude (向量长度)
 * - normalized (归一化)
 * - withDeadzone (死区处理)
 * - 边界情况 (零向量、极大值、负值)
 */
class Vector2Test { // 声明测试类 Vector2Test，集中测试向量数学运算

    // ===== magnitude 测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `零向量的magnitude为0`() { // 测试用例：零向量的模长（magnitude）应为 0
        assertEquals(0f, Vector2.ZERO.magnitude, 0.001f) // 语法：assertEquals(期望,实际,delta)；delta=0.001f 为浮点误差允许范围；断言零向量模长为 0
    } // 结束「零向量的magnitude为0」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `单位向量的magnitude为1`() { // 测试用例：单位向量的模长应为 1
        assertEquals(1f, Vector2(1f, 0f).magnitude, 0.001f) // 断言向量(1,0)模长为 1（delta=0.001f）
        assertEquals(1f, Vector2(0f, 1f).magnitude, 0.001f) // 断言向量(0,1)模长为 1（delta=0.001f）
        assertEquals(1f, Vector2(-1f, 0f).magnitude, 0.001f) // 断言向量(-1,0)模长为 1（delta=0.001f）
    } // 结束「单位向量的magnitude为1」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `对角向量的magnitude为sqrt2`() { // 测试用例：对角向量的模长应为 √2
        val expected = kotlin.math.sqrt(2f) // 语法：val 声明只读变量；用 kotlin.math.sqrt 计算 2 的平方根作为期望值
        assertEquals(expected, Vector2(1f, 1f).magnitude, 0.001f) // 断言向量(1,1)模长 ≈ √2（delta=0.001f）
        assertEquals(expected, Vector2(-1f, -1f).magnitude, 0.001f) // 断言向量(-1,-1)模长 ≈ √2（delta=0.001f）
    } // 结束「对角向量的magnitude为sqrt2」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `magnitude不超过sqrt2`() { // 测试用例：模长不应超过 √2（摇杆满幅上限）
        // 摇杆最大值为 (1,1)，magnitude = sqrt(2) ≈ 1.414
        val v = Vector2(1f, 1f) // 语法：val 声明只读变量；构造向量(1,1)，即摇杆最大输入
        assertTrue(v.magnitude <= kotlin.math.sqrt(2f)) // 语法：assertTrue 断言为真；断言模长不超过 √2
    } // 结束「magnitude不超过sqrt2」测试方法

    // ===== normalized 测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `归一化后magnitude为1`() { // 测试用例：归一化后向量的模长应为 1
        val v = Vector2(3f, 4f)  // magnitude = 5 // 语法：val 声明只读变量；构造向量(3,4)，模长为 5
        val normalized = v.normalized() // 语法：val 声明只读变量；调用 normalized() 归一化，得到单位向量
        assertEquals(1f, normalized.magnitude, 0.001f) // 断言归一化后模长为 1（delta=0.001f）
    } // 结束「归一化后magnitude为1」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `归一化保持方向`() { // 测试用例：归一化应保持原方向
        val v = Vector2(1f, 0f) // 语法：val 声明只读变量；构造向量(1,0)，指向正 X 方向
        val normalized = v.normalized() // 语法：val 声明只读变量；调用 normalized() 归一化
        assertEquals(1f, normalized.x, 0.001f) // 断言归一化后 x=1（方向不变，仍指向正X）
        assertEquals(0f, normalized.y, 0.001f) // 断言归一化后 y=0
    } // 结束「归一化保持方向」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `归一化负方向`() { // 测试用例：负方向向量归一化应保持负方向
        val v = Vector2(-1f, 0f) // 语法：val 声明只读变量；构造向量(-1,0)，指向负 X 方向
        val normalized = v.normalized() // 语法：val 声明只读变量；调用 normalized() 归一化
        assertEquals(-1f, normalized.x, 0.001f) // 断言归一化后 x=-1（方向不变，仍指向负X）
        assertEquals(0f, normalized.y, 0.001f) // 断言归一化后 y=0
    } // 结束「归一化负方向」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `零向量归一化返回零向量`() { // 测试用例：零向量归一化应返回零向量（避免除零）
        val normalized = Vector2.ZERO.normalized() // 语法：val 声明只读变量；对零向量调用 normalized()
        assertEquals(0f, normalized.x, 0.001f) // 断言零向量归一化后 x=0
        assertEquals(0f, normalized.y, 0.001f) // 断言零向量归一化后 y=0
    } // 结束「零向量归一化返回零向量」测试方法

    // ===== withDeadzone 测试 =====

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `死区内输入归零`() { // 测试用例：模长在死区内的输入应归零
        val v = Vector2(0.05f, 0.03f)  // magnitude ≈ 0.058 // 语法：val 声明只读变量；构造向量(0.05,0.03)，模长约 0.058 小于死区
        val result = v.withDeadzone(0.15f) // 语法：val 声明只读变量；调用 withDeadzone(0.15f) 应用死区
        assertEquals(0f, result.x, 0.001f) // 断言死区内输入归零后 x=0
        assertEquals(0f, result.y, 0.001f) // 断言死区内输入归零后 y=0
    } // 结束「死区内输入归零」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `死区外输入保留`() { // 测试用例：满幅（模长=1）输入应被完整保留
        val v = Vector2(1f, 0f)  // magnitude = 1 // 语法：val 声明只读变量；构造向量(1,0)，模长为 1 为满幅输入
        val result = v.withDeadzone(0.15f) // 语法：val 声明只读变量；调用 withDeadzone(0.15f) 应用死区
        // (1 - 0.15) / (1 - 0.15) = 1.0
        assertEquals(1f, result.x, 0.001f) // 断言满幅输入 x 保持不变为 1
        assertEquals(0f, result.y, 0.001f) // 断言 y 保持为 0
    } // 结束「死区外输入保留」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `死区边界外输入被缩放`() { // 测试用例：模长大于死区的输入应按比例缩放
        // magnitude = 0.5, deadzone = 0.15
        // scale = (0.5 - 0.15) / (1 - 0.15) = 0.35 / 0.85 ≈ 0.4118
        val v = Vector2(0.5f, 0f) // 语法：val 声明只读变量；构造向量(0.5,0)，模长 0.5 大于死区 0.15
        val result = v.withDeadzone(0.15f) // 语法：val 声明只读变量；调用 withDeadzone(0.15f) 应用死区
        val expectedScale = (0.5f - 0.15f) / (1f - 0.15f) // 语法：val 声明只读变量；按死区公式计算期望缩放比例
        assertEquals(expectedScale, result.x, 0.001f) // 断言缩放后的 x 等于期望比例（delta=0.001f）
        assertEquals(0f, result.y, 0.001f) // 断言 y 保持为 0
    } // 结束「死区边界外输入被缩放」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `零死区不改变输入`() { // 测试用例：死区为 0 时输入不应被改变
        val v = Vector2(0.5f, 0.3f) // 语法：val 声明只读变量；构造向量(0.5,0.3)
        val result = v.withDeadzone(0f) // 语法：val 声明只读变量；调用 withDeadzone(0f) 零死区处理
        // 死区为0时，scale = mag / 1 = mag，但归一化后乘以mag，所以等于原值
        assertEquals(v.x, result.x, 0.01f) // 断言 x 保持原值（delta=0.01f 宽松误差）
        assertEquals(v.y, result.y, 0.01f) // 断言 y 保持原值（delta=0.01f 宽松误差）
    } // 结束「零死区不改变输入」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `死区为1时所有非满幅输入归零`() { // 测试用例：死区为 1 时，模长小于 1 的所有输入应归零
        val v = Vector2(0.5f, 0.5f)  // magnitude ≈ 0.707 // 语法：val 声明只读变量；构造向量(0.5,0.5)，模长约 0.707
        val result = v.withDeadzone(1f) // 语法：val 声明只读变量；调用 withDeadzone(1f) 死区为 1
        assertEquals(0f, result.x, 0.001f) // 断言非满幅输入归零后 x=0
        assertEquals(0f, result.y, 0.001f) // 断言非满幅输入归零后 y=0
    } // 结束「死区为1时所有非满幅输入归零」测试方法

    @Test // 语法：@Test 注解，声明该方法为一个 JUnit 测试用例
    fun `死区恰好等于magnitude时归零`() { // 测试用例：死区恰好等于模长时应归零（边界条件）
        val v = Vector2(0.15f, 0f)  // magnitude = 0.15 // 语法：val 声明只读变量；构造向量(0.15,0)，模长恰为 0.15
        val result = v.withDeadzone(0.15f) // 语法：val 声明只读变量；调用 withDeadzone(0.15f) 死区与模长相等
        // mag < deadzone → 归零
        assertEquals(0f, result.x, 0.001f) // 断言边界情况归零，x=0
        assertEquals(0f, result.y, 0.001f) // 断言边界情况归零，y=0
    } // 结束「死区恰好等于magnitude时归零」测试方法
} // 结束 Vector2Test 测试类
