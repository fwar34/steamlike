package com.steamlike.controller.injection

import android.view.KeyEvent

/**
 * 桥接输入注入器
 *
 * 实现 [InputInjector] 接口，将键盘/鼠标事件通过 [InputBridgeServer] 的TCP连接
 * 发送给运行在Winlator内的Windows配套程序。
 *
 * ## 工作原理
 * ```
 * Android KeyCode (如 KEYCODE_SPACE=62)
 *      ↓ androidKeyCodeToWindowsVK()
 * Windows VK Code (如 VK_SPACE=0x20)
 *      ↓ InputBridgeServer.sendKeyEvent()
 * TCP数据包 → Windows客户端
 *      ↓
 * Windows SendInput() → WoW游戏接收
 * ```
 *
 * ## 架构优势
 * | 特性 | 说明 |
 * |------|------|
 * | 注入位置 | Windows应用层(Winlator内) |
 * | 依赖 | 仅需悬浮窗权限 + Windows配套程序 |
 * | 延迟 | 低(直接到Windows) |
 * | 可靠性 | 高(SendInput稳定) |
 * | 手柄读取 | GamepadInputView焦点窗口捕获(无需Shizuku) |
 *
 * @param server TCP服务器实例
 */
class BridgeInputInjector(
    private val server: InputBridgeServer
) : InputInjector {

    /** 当前按下的键集合（用于releaseAll） */
    private val pressedKeys = mutableSetOf<Int>()

    /** 当前按下的鼠标按钮集合 */
    private val pressedButtons = mutableSetOf<MouseButton>()

    override fun isAvailable(): Boolean {
        // 服务器可用即认为注入器可用（客户端可能尚未连接，但事件会被缓存）
        return true
    }

    /**
     * 按下键盘按键
     * @param keyCode Android KeyEvent.KEYCODE_* 常量
     */
    override fun sendKeyDown(keyCode: Int) {
        val vkCode = androidKeyCodeToWindowsVK(keyCode)
        if (vkCode != 0) {
            pressedKeys.add(vkCode)
            server.sendKeyEvent(vkCode, isDown = true)
        }
    }

    /**
     * 释放键盘按键
     * @param keyCode Android KeyEvent.KEYCODE_* 常量
     */
    override fun sendKeyUp(keyCode: Int) {
        val vkCode = androidKeyCodeToWindowsVK(keyCode)
        if (vkCode != 0) {
            pressedKeys.remove(vkCode)
            server.sendKeyEvent(vkCode, isDown = false)
        }
    }

    override fun sendMouseDown(button: MouseButton) {
        val btnId = when (button) {
            MouseButton.LEFT -> 0
            MouseButton.RIGHT -> 1
            MouseButton.MIDDLE -> 2
        }
        pressedButtons.add(button)
        server.sendMouseButton(btnId, isDown = true)
    }

    override fun sendMouseUp(button: MouseButton) {
        val btnId = when (button) {
            MouseButton.LEFT -> 0
            MouseButton.RIGHT -> 1
            MouseButton.MIDDLE -> 2
        }
        pressedButtons.remove(button)
        server.sendMouseButton(btnId, isDown = false)
    }

    override fun sendMouseMove(dx: Float, dy: Float) {
        server.sendMouseMove(dx, dy)
    }

    /**
     * 释放所有按下的键和按钮
     * 通知Windows客户端执行释放操作
     */
    override fun releaseAll() {
        // 本地状态清理
        pressedKeys.clear()
        pressedButtons.clear()
        // 通知Windows客户端释放所有
        server.sendReleaseAll()
    }

    override fun destroy() {
        releaseAll()
    }

    // ====================================================================
    // Android KeyCode → Windows VK Code 映射表
    // ====================================================================

    /**
     * 将Android KeyCode转换为Windows虚拟键码
     *
     * Windows VK Code参考: https://docs.microsoft.com/en-us/windows/win32/inputdev/virtual-key-codes
     *
     * @param androidKeyCode Android KeyEvent.KEYCODE_* 常量
     * @return Windows VK Code，0=无对应映射
     */
    private fun androidKeyCodeToWindowsVK(androidKeyCode: Int): Int {
        return when (androidKeyCode) {
            // ===== 字母 A-Z =====
            KeyEvent.KEYCODE_A -> 0x41  // VK_A
            KeyEvent.KEYCODE_B -> 0x42
            KeyEvent.KEYCODE_C -> 0x43
            KeyEvent.KEYCODE_D -> 0x44
            KeyEvent.KEYCODE_E -> 0x45
            KeyEvent.KEYCODE_F -> 0x46
            KeyEvent.KEYCODE_G -> 0x47
            KeyEvent.KEYCODE_H -> 0x48
            KeyEvent.KEYCODE_I -> 0x49
            KeyEvent.KEYCODE_J -> 0x4A
            KeyEvent.KEYCODE_K -> 0x4B
            KeyEvent.KEYCODE_L -> 0x4C
            KeyEvent.KEYCODE_M -> 0x4D
            KeyEvent.KEYCODE_N -> 0x4E
            KeyEvent.KEYCODE_O -> 0x4F
            KeyEvent.KEYCODE_P -> 0x50
            KeyEvent.KEYCODE_Q -> 0x51
            KeyEvent.KEYCODE_R -> 0x52
            KeyEvent.KEYCODE_S -> 0x53
            KeyEvent.KEYCODE_T -> 0x54
            KeyEvent.KEYCODE_U -> 0x55
            KeyEvent.KEYCODE_V -> 0x56
            KeyEvent.KEYCODE_W -> 0x57
            KeyEvent.KEYCODE_X -> 0x58
            KeyEvent.KEYCODE_Y -> 0x59
            KeyEvent.KEYCODE_Z -> 0x5A

            // ===== 数字 0-9 =====
            KeyEvent.KEYCODE_0 -> 0x30  // VK_0
            KeyEvent.KEYCODE_1 -> 0x31
            KeyEvent.KEYCODE_2 -> 0x32
            KeyEvent.KEYCODE_3 -> 0x33
            KeyEvent.KEYCODE_4 -> 0x34
            KeyEvent.KEYCODE_5 -> 0x35
            KeyEvent.KEYCODE_6 -> 0x36
            KeyEvent.KEYCODE_7 -> 0x37
            KeyEvent.KEYCODE_8 -> 0x38
            KeyEvent.KEYCODE_9 -> 0x39

            // ===== 功能键 =====
            KeyEvent.KEYCODE_F1 -> 0x70   // VK_F1
            KeyEvent.KEYCODE_F2 -> 0x71
            KeyEvent.KEYCODE_F3 -> 0x72
            KeyEvent.KEYCODE_F4 -> 0x73
            KeyEvent.KEYCODE_F5 -> 0x74
            KeyEvent.KEYCODE_F6 -> 0x75
            KeyEvent.KEYCODE_F7 -> 0x76
            KeyEvent.KEYCODE_F8 -> 0x77
            KeyEvent.KEYCODE_F9 -> 0x78
            KeyEvent.KEYCODE_F10 -> 0x79
            KeyEvent.KEYCODE_F11 -> 0x7A
            KeyEvent.KEYCODE_F12 -> 0x7B

            // ===== 修饰键 =====
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xA0   // VK_LSHIFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA1  // VK_RSHIFT
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xA2    // VK_LCONTROL
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA3   // VK_RCONTROL
            KeyEvent.KEYCODE_ALT_LEFT -> 0xA4     // VK_LMENU
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xA5    // VK_RMENU

            // ===== 特殊键 =====
            KeyEvent.KEYCODE_SPACE -> 0x20        // VK_SPACE
            KeyEvent.KEYCODE_ENTER -> 0x0D        // VK_RETURN
            KeyEvent.KEYCODE_ESCAPE -> 0x1B       // VK_ESCAPE
            KeyEvent.KEYCODE_TAB -> 0x09          // VK_TAB
            KeyEvent.KEYCODE_BACK -> 0x08         // VK_BACK (Backspace)
            KeyEvent.KEYCODE_DEL -> 0x2E          // VK_DELETE
            KeyEvent.KEYCODE_INSERT -> 0x2D       // VK_INSERT
            KeyEvent.KEYCODE_HOME -> 0x24         // VK_HOME
            KeyEvent.KEYCODE_PAGE_UP -> 0x21      // VK_PRIOR
            KeyEvent.KEYCODE_PAGE_DOWN -> 0x22    // VK_NEXT
            KeyEvent.KEYCODE_MOVE_END -> 0x23     // VK_END

            // ===== 方向键 =====
            KeyEvent.KEYCODE_DPAD_UP -> 0x26      // VK_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x28    // VK_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x25    // VK_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27   // VK_RIGHT

            // ===== 符号键 =====
            KeyEvent.KEYCODE_MINUS -> 0xBD        // VK_OEM_MINUS "-"
            KeyEvent.KEYCODE_EQUALS -> 0xBB       // VK_OEM_PLUS "="
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB // VK_OEM_4 "["
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD // VK_OEM_6 "]"
            KeyEvent.KEYCODE_BACKSLASH -> 0xDC     // VK_OEM_5 "\"
            KeyEvent.KEYCODE_SEMICOLON -> 0xBA     // VK_OEM_1 ";"
            KeyEvent.KEYCODE_APOSTROPHE -> 0xDE    // VK_OEM_7 "'"
            KeyEvent.KEYCODE_COMMA -> 0xBC         // VK_OEM_COMMA ","
            KeyEvent.KEYCODE_PERIOD -> 0xBE        // VK_OEM_PERIOD "."
            KeyEvent.KEYCODE_SLASH -> 0xBF         // VK_OEM_2 "/"

            // ===== 小键盘 =====
            KeyEvent.KEYCODE_NUM_LOCK -> 0x90     // VK_NUMLOCK
            KeyEvent.KEYCODE_NUMPAD_0 -> 0x60     // VK_NUMPAD0
            KeyEvent.KEYCODE_NUMPAD_1 -> 0x61
            KeyEvent.KEYCODE_NUMPAD_2 -> 0x62
            KeyEvent.KEYCODE_NUMPAD_3 -> 0x63
            KeyEvent.KEYCODE_NUMPAD_4 -> 0x64
            KeyEvent.KEYCODE_NUMPAD_5 -> 0x65
            KeyEvent.KEYCODE_NUMPAD_6 -> 0x66
            KeyEvent.KEYCODE_NUMPAD_7 -> 0x67
            KeyEvent.KEYCODE_NUMPAD_8 -> 0x68
            KeyEvent.KEYCODE_NUMPAD_9 -> 0x69

            // ===== 其他 =====
            KeyEvent.KEYCODE_CAPS_LOCK -> 0x14     // VK_CAPITAL
            KeyEvent.KEYCODE_SCROLL_LOCK -> 0x91   // VK_SCROLL

            else -> 0  // 无对应映射
        }
    }
}
