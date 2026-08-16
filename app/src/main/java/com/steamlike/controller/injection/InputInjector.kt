package com.steamlike.controller.injection

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 输入注入器接口
 *
 * ## 设计目的
 * 将手柄映射后的键盘/鼠标事件注入到目标应用（Winlator 内的 WoW 游戏）。
 * 使用接口抽象是为了支持多种注入实现，便于未来扩展。
 *
 * ## 当前实现
 * - [BridgeInputInjector]: 通过 TCP 桥接到 Windows，使用 SendInput() 注入
 *
 * ## 未来可能的实现
 * - LocalInputInjector: 直接注入 Android 系统（需要 root 或 Shizuku）
 * - UsbInjector: 通过 USB HID 注入
 *
 * ## 使用流程
 * ```kotlin
 * val injector: InputInjector = BridgeInputInjector(server)
 * if (injector.isAvailable()) {
 *     injector.sendKeyPress(KeyEvent.KEYCODE_SPACE)  // 模拟按下空格键
 *     injector.sendMouseClick(MouseButton.LEFT)       // 模拟鼠标左键点击
 * }
 * ```
 *
 * ## 线程安全
 * 实现类需保证线程安全，因为注入操作可能在多个线程调用:
 * - 主线程的按钮回调（onPressed/onReleased）
 * - 主线程的摇杆回调（onValueChanged）
 * - 服务销毁时调用 [destroy]
 */
interface InputInjector {

    /**
     * 检查注入器是否可用
     *
     * 在调用其他方法前应先检查此方法，避免注入失败。
     *
     * @return true=可用，false=不可用（如 TCP 服务器未启动）
     */
    fun isAvailable(): Boolean

    /**
     * 按下键盘按键
     *
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量（如 [KeyEvent.KEYCODE_SPACE]）
     */
    fun sendKeyDown(keyCode: Int)

    /**
     * 释放键盘按键
     *
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量
     */
    fun sendKeyUp(keyCode: Int)

    /**
     * 按键点击（按下 + 立即释放）
     *
     * 默认实现调用 [sendKeyDown] 后立即调用 [sendKeyUp]。
     * 适用于不需要长按的场景（如快捷栏按键）。
     *
     * @param keyCode Android [KeyEvent.KEYCODE_*] 常量
     */
    fun sendKeyPress(keyCode: Int) {
        sendKeyDown(keyCode)
        sendKeyUp(keyCode)
    }

    /**
     * 鼠标按下
     *
     * @param button 鼠标按钮（[MouseButton.LEFT] / [MouseButton.RIGHT] / [MouseButton.MIDDLE]）
     */
    fun sendMouseDown(button: MouseButton)

    /**
     * 鼠标释放
     *
     * @param button 鼠标按钮
     */
    fun sendMouseUp(button: MouseButton)

    /**
     * 鼠标点击（按下 + 立即释放）
     *
     * 默认实现调用 [sendMouseDown] 后立即调用 [sendMouseUp]。
     *
     * @param button 鼠标按钮
     */
    fun sendMouseClick(button: MouseButton) {
        sendMouseDown(button)
        sendMouseUp(button)
    }

    /**
     * 鼠标相对移动
     *
     * 用于摇杆控制视角或光标。dx/dy 为相对位移（非绝对坐标）。
     *
     * @param dx X 轴相对位移（像素，右为正）
     * @param dy Y 轴相对位移（像素，下为正）
     */
    fun sendMouseMove(dx: Float, dy: Float)

    /**
     * 释放所有按下的键和按钮
     *
     * 在以下场景调用:
     * - 切换操作层时（防止按键卡住）
     * - 服务停止时
     * - 客户端断开连接时
     */
    fun releaseAll()

    /**
     * 销毁注入器，释放资源
     *
     * 在服务 onDestroy 时调用。
     */
    fun destroy()
}

/**
 * 鼠标按钮枚举
 *
 * 用于 [InputInjector.sendMouseDown] / [InputInjector.sendMouseUp] 等方法。
 */
enum class MouseButton {
    /** 左键（主键，用于点击/选择） */
    LEFT,
    /** 右键（次键，用于上下文菜单/视角控制） */
    RIGHT,
    /** 中键（滚轮按下，用于自动滚动等） */
    MIDDLE
}
