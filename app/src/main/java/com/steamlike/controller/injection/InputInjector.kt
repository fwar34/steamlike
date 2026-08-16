package com.steamlike.controller.injection

import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 输入注入器接口
 *
 * 将键盘/鼠标事件注入到Android系统，被前台应用（Winlator）接收。
 */
interface InputInjector {

    /** 检查注入器是否可用 */
    fun isAvailable(): Boolean

    /** 按下键盘按键 */
    fun sendKeyDown(keyCode: Int)

    /** 释放键盘按键 */
    fun sendKeyUp(keyCode: Int)

    /** 按键点击（按下+释放） */
    fun sendKeyPress(keyCode: Int) {
        sendKeyDown(keyCode)
        sendKeyUp(keyCode)
    }

    /** 鼠标按下 */
    fun sendMouseDown(button: MouseButton)

    /** 鼠标释放 */
    fun sendMouseUp(button: MouseButton)

    /** 鼠标点击（按下+释放） */
    fun sendMouseClick(button: MouseButton) {
        sendMouseDown(button)
        sendMouseUp(button)
    }

    /** 鼠标相对移动 */
    fun sendMouseMove(dx: Float, dy: Float)

    /** 释放所有按下的键和按钮 */
    fun releaseAll()

    /** 销毁 */
    fun destroy()
}

enum class MouseButton { LEFT, RIGHT, MIDDLE }
