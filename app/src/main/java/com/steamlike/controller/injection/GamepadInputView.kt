package com.steamlike.controller.injection

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowInsets

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
 * ## 悬浮窗切换快捷键
 * 手柄 Home 键（KEYCODE_BUTTON_MODE）切换悬浮窗展开/收起/映射列表。
 * 同时支持物理键盘 Ctrl+Shift+X。
 *
 * @param context Context
 */
class GamepadInputView(context: Context) : View(context) {

    companion object {
        private const val TAG = "GamepadInputView"

        /**
         * 需要经按键事件通道（onImeKey）转发的控制键集合。
         *
         * 字母/数字/符号等可打印键由 commitText/setComposingText 文本通道注入，
         * 若按键事件通道也转发会重复注入（"按一个字母出现两个"）。
         * 只有这些真正的控制键（没有对应文本）才走按键事件通道转发。
         */
        private val CONTROL_KEY_CODES = setOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
        )

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
                // 软输入模式：不自动弹出也不调整布局。
                // 注意不能设 SOFT_INPUT_STATE_ALWAYS_HIDDEN（会阻止 showSoftInput 弹出键盘，
                // 键盘需绑定到本窗口才能把输入经 IME 转发到 Windows 注入）
                softInputMode = LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED or
                    LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            }
        }
    }

    /** KeyEvent 回调（按钮按下/释放），返回 true=已处理 */
    var onKeyEvent: ((KeyEvent) -> Boolean)? = null

    /** MotionEvent 回调（摇杆/扳机），返回 true=已处理 */
    var onGenericMotion: ((MotionEvent) -> Boolean)? = null

    /** 悬浮窗切换回调（组合键触发），在主线程调用 */
    var onToggleOverlay: (() -> Unit)? = null

    /**
     * 是否正在捕获（历史字段，当前无实际作用）
     *
     * 早期实现里映射器依据本字段决定是否处理非切换动作。现在捕获/暂停由
     * [ControllerOverlayService.isCapturing] 统一管理：**暂停捕获时整个
     * GamepadInputView 会被 WindowManager 移除**（恢复侧滑返回手势），事件根本
     * 到不了本 View；恢复捕获时才重新创建窗口。本字段已不再被读取，保留仅为
     * 兼容旧逻辑。
     */
    var isCapturing: Boolean = true

    /**
     * IME 提交/更新的单个字符回调（用于转发到 Windows 注入 WoW）
     *
     * - 普通字符: 直接转发该字符
     * - '\b': 退格（删除光标前字符）
     * - '\u007F': 向前删除（Delete 键）
     *
     * 由 [onCreateInputConnection] 返回的 InputConnection 在 IME 回调时触发，
     * 均在主线程调用。
     */
    var onImeChar: ((Char) -> Unit)? = null

    /**
     * IME 特殊按键事件回调（回车/方向键等控制键）
     *
     * 退格/删除按键已被 InputConnection 显式处理（转为 onImeChar 的 '\b'），
     * 字母/数字/符号走文本通道（commitText/setComposingText），均不回调到这里。
     */
    var onImeKey: ((KeyEvent) -> Unit)? = null

    /**
     * IME 关闭回调：输入连接被关闭（用户点击键盘自身隐藏按钮/返回键/输入法失活）。
     * 应用无法直接收到键盘自身隐藏的系统回调，此回调让服务端能恢复捕获。
     */
    var onImeClosed: (() -> Unit)? = null

    /** 返回键在 IME 处理前到达本 View（键盘打开时用户按返回）→ 通知服务端恢复捕获 */
    var onImeBackPressed: (() -> Unit)? = null

    /** 窗口 insets 显示键盘已收起（键盘自身隐藏按钮/下滑收起等）→ 通知服务端恢复捕获 */
    var onImeHidden: (() -> Unit)? = null

    /** 当前 IME 组合文本（用于 diff 计算，避免组合过程重复注入字符） */
    private var composingText = ""

    /**
     * 抑制标志：deleteSurroundingText 已处理退格后，输入法可能以
     * sendKeyEvent(KEYCODE_DEL) 回退重试，抑制该次回退避免重复删除
     */
    private var suppressDelFromDeleteSurrounding = false

    init {
        // 必须设置可获焦点，才能接收 KeyEvent
        isFocusable = true
        isFocusableInTouchMode = true
        // 监听 IME 可见性变化：点击键盘自身隐藏按钮时系统没有任何回调到服务端，
        // 只能通过本窗口的 insets 变化（键盘收起 → ime 不可见）来驱动恢复捕获
        setOnApplyWindowInsetsListener { _, insets ->
            val imeVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                insets.isVisible(WindowInsets.Type.ime())
            Log.i(TAG, "onApplyWindowInsets imeVisible=$imeVisible")
            if (!imeVisible) {
                onImeHidden?.invoke()
            }
            insets
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * 为软键盘提供 InputConnection，使键盘能绑定到本窗口并捕获输入
     *
     * IME 只能绑定本进程有焦点的窗口。Winlator 是独立进程，软键盘无法直接
     * 绑定到它；因此让键盘绑定到本 1x1 焦点窗口，将输入转发到 Windows 注入。
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val s = text?.toString() ?: ""
                Log.d(TAG, "commitText text='$s' composing='$composingText'")
                // 提交会替换当前组合区：先按组合文本回退，再注入新文本
                if (composingText.isNotEmpty()) {
                    repeat(composingText.length) { onImeChar?.invoke('\b') }
                    composingText = ""
                }
                s.forEach { onImeChar?.invoke(it) }
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val newText = text?.toString() ?: ""
                Log.d(TAG, "setComposingText text='$newText' oldComposing='$composingText'")
                // 与旧组合文本求差异：缩短的部分回退，新增的部分注入，
                // 使英文逐字输入时字符能即时到达游戏
                var common = 0
                while (common < composingText.length && common < newText.length &&
                    composingText[common] == newText[common]
                ) {
                    common++
                }
                repeat(composingText.length - common) { onImeChar?.invoke('\b') }
                for (i in common until newText.length) onImeChar?.invoke(newText[i])
                composingText = newText
                return super.setComposingText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                Log.d(TAG, "deleteSurroundingText before=$beforeLength after=$afterLength")
                // 显式转发退格/删除
                repeat(beforeLength) { onImeChar?.invoke('\b') }
                repeat(afterLength) { onImeChar?.invoke('\u007F') }
                // 非编辑框连接 deleteSurroundingText 会返回 false，输入法常随后以
                // sendKeyEvent(KEYCODE_DEL) 回退重试；标记抑制该次回退避免重复删除
                if (beforeLength > 0 || afterLength > 0) {
                    suppressDelFromDeleteSurrounding = true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                // 退格/删除在按键事件通道统一处理：Gboard 等输入法对非编辑框连接
                // 以按键事件（而非 deleteSurroundingText）发送退格，此前 DEL 被丢弃
                // 导致退格永远到不了游戏。
                // - KEYCODE_DEL（退格键）→ 回退 '\b'（VK_BACK）
                // - KEYCODE_FORWARD_DEL（Delete 键）→ 向前删除 '\u007F'（VK_DELETE）
                // - 控制键（回车/方向/翻页等）→ onImeKey 转发
                // - 可打印键（字母/数字/符号）→ 不转发！它们已由 commitText/
                //   setComposingText 文本通道注入，若这里再经 onImeKey 转发会重复注入
                //   （表现为"按一个字母出现两个"）
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            if (suppressDelFromDeleteSurrounding) {
                                suppressDelFromDeleteSurrounding = false
                            } else {
                                onImeChar?.invoke('\b')
                            }
                        }
                        KeyEvent.KEYCODE_FORWARD_DEL -> onImeChar?.invoke('\u007F')
                        else -> {
                            if (event.keyCode in CONTROL_KEY_CODES) onImeKey?.invoke(event)
                            else Log.d(TAG, "sendKeyEvent drop printable key=${event.keyCode} " +
                                "char=${event.unicodeChar}")
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                // 软键盘"发送/回车"动作 → 注入回车
                val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
                val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
                onImeKey?.invoke(down)
                onImeKey?.invoke(up)
                return super.performEditorAction(actionCode)
            }

            override fun closeConnection() {
                Log.i(TAG, "InputConnection closed")
                super.closeConnection()
                // 键盘自身隐藏按钮/输入法失活 → 输入连接被关闭，通知服务端恢复捕获
                onImeClosed?.invoke()
            }
        }
    }

    /**
     * 键盘打开时按返回键（BACK）会先经此回调到达本 View。
     *
     * 若 IME 未消费该返回键，则回到默认返回行为；这里统一通知服务端
     * 键盘已收起并恢复捕获（insets 监听可能因窗口特性不触发，作为兜底）。
     */
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            Log.i(TAG, "onKeyPreIme BACK pressed, notify service")
            onImeBackPressed?.invoke()
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "dispatchKeyEvent act=${event.action} key=${event.keyCode} " +
            "src=${event.source} rep=${event.repeatCount} unicode=${event.unicodeChar}")
        // 始终转发到映射器，由映射器根据 isCapturing 决定是否处理
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
        Log.i(TAG, "onWindowFocusChanged hasFocus=$hasWindowFocus")
        if (hasWindowFocus) {
            requestFocus()
            // 延迟再次请求焦点，确保在窗口动画完成后仍获得焦点
            postDelayed({ requestFocus() }, 200)
        }
    }
}
