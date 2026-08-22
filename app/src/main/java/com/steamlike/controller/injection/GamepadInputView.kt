package com.steamlike.controller.injection

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.graphics.PixelFormat
import android.os.Build

/**
 * 透明全屏手柄输入捕获View
 *
 * ## 原理
 * 替代 Shizuku + getevent 方案。通过 WindowManager 添加一个全屏透明的、
 * **不可触摸但可获焦点** 的窗口来接收手柄的 KeyEvent 和 MotionEvent。
 *
 * - `FLAG_NOT_TOUCHABLE`: 触摸事件穿透到下层（Winlator），用户仍可触摸操作 Winlator
 * - 不设 `FLAG_NOT_FOCUSABLE`: 窗口可获焦点，能接收手柄按键/摇杆事件
 * - 全屏透明: 不遮挡 Winlator 画面
 *
 * ## 事件流
 * ```
 * 手柄硬件 → Android InputManager → GamepadInputView（焦点窗口）
 *      ├─ dispatchKeyEvent()        → 按钮按下/释放（A/B/X/Y/LB/RB/D-Pad...）
 *      └─ dispatchGenericMotionEvent() → 摇杆移动/扳机按压（左/右摇杆, LT/RT）
 * ```
 *
 * ## 与Shizuku方案的对比
 * | 特性        | Shizuku(getevent)       | GamepadInputView(焦点)         |
 * |------------|-------------------------|--------------------------------|
 * | 依赖        | 需安装Shizuku应用+授权    | 仅需悬浮窗权限                  |
 * | 触摸穿透    | 不影响触摸               | FLAG_NOT_TOUCHABLE穿透          |
 * | 摇杆精度    | 原始轴值(0~255)          | Android已归一化(-1.0~1.0)       |
 * | 设备识别    | 需手动解析getevent设备   | Android自动识别手柄设备          |
 * | 稳定性      | 依赖getevent进程         | 系统原生事件分发                 |
 *
 * @param context Context
 */
class GamepadInputView(context: Context) : View(context) {

    /** KeyEvent 回调（按钮按下/释放），返回 true=已处理 */
    var onKeyEvent: ((KeyEvent) -> Boolean)? = null

    /** MotionEvent 回调（摇杆/扳机），返回 true=已处理 */
    var onGenericMotion: ((MotionEvent) -> Boolean)? = null

    /** 悬浮窗切换回调（Ctrl+Alt+Shift+X 触发），在主线程调用 */
    var onToggleOverlay: (() -> Unit)? = null

    init {
        // 必须设置可获焦点，才能接收 KeyEvent
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Ctrl+Alt+Shift+X 切换悬浮窗，仅本 APK 消费，不发送到 Windows
        if (event.action == KeyEvent.ACTION_DOWN
            && event.keyCode == KeyEvent.KEYCODE_X
            && event.isCtrlPressed && event.isAltPressed && event.isShiftPressed
        ) {
            onToggleOverlay?.invoke()
            return true
        }
        val handled = onKeyEvent?.invoke(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = onGenericMotion?.invoke(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    /**
     * 窗口焦点变化时自动重新请求焦点
     *
     * 当用户切到其他应用再切回时，窗口可能失去焦点。
     * 此回调在窗口重新获得焦点时自动 requestFocus()。
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            requestFocus()
            // 延迟再次请求焦点，确保在窗口动画完成后仍获得焦点
            postDelayed({ requestFocus() }, 200)
        }
    }

    companion object {
        /**
         * 创建用于添加到 WindowManager 的 LayoutParams
         *
         * - **1x1 像素尺寸**: 不覆盖屏幕，避免影响系统返回手势（Android 14+ 预测式返回
         *   基于窗口层次判定，全屏窗口即使 FLAG_NOT_TOUCHABLE 也会被系统认为遮挡边缘）
         * - 透明背景（PixelFormat.TRANSLUCENT）
         * - FLAG_NOT_TOUCHABLE: 触摸穿透到下层应用
         * - 不含 FLAG_NOT_FOCUSABLE: 可获焦点接收手柄事件（手柄事件通过 InputManager
         *   路由到焦点窗口，与 View 尺寸无关）
         * - FLAG_LAYOUT_IN_SCREEN: 覆盖整个屏幕含状态栏区域
         * - FLAG_LAYOUT_NO_LIMITS: 不受屏幕边界限制
         */
        fun createLayoutParams(): WindowManager.LayoutParams {
            return LayoutParams(
                1,
                1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    LayoutParams.TYPE_PHONE,
                LayoutParams.FLAG_NOT_TOUCHABLE or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 设置软输入模式：不调整布局，避免焦点窗口被IME遮挡
                softInputMode = LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }
        }
    }
}
