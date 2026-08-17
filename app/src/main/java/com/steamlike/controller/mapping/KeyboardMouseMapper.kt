package com.steamlike.controller.mapping

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerStick
import com.steamlike.controller.core.KeyMapping
import com.steamlike.controller.core.MappedAction
import com.steamlike.controller.core.MouseButton
import com.steamlike.controller.core.SteamInput
import com.steamlike.controller.injection.InputInjector

/**
 * 键盘/鼠标映射器
 *
 * 监听 [SteamInput] 的映射回调，将手柄按键映射转换为键盘/鼠标事件，
 * 通过 [InputInjector] 注入到目标应用。
 *
 * ## 子命令（Sub-Command）处理
 *
 * 当按键映射包含子命令时，按以下顺序注入:
 * 1. 按下主键
 * 2. 按下所有子命令键
 * 3. 松开所有子命令键
 * 4. 松开主键
 *
 * 示例: 手柄X → KeyMapping(KeyboardKey(Alt), subCommands=[KEYCODE_3])
 * ```
 * 按下X: sendKeyDown(Alt) → sendKeyDown(3)
 * 松开X: sendKeyUp(3) → sendKeyUp(Alt)
 * 最终输出: Alt+3 组合键
 * ```
 *
 * ## 摇杆处理
 *
 * - 左摇杆: 默认不映射（可在设置中配置为 MouseMove）
 * - 右摇杆: 默认映射为视角控制（LookAround），发送鼠标相对移动
 * - 灵敏度由 [SteamInput.profile] 的 [com.steamlike.controller.core.GlobalSettings] 控制
 *
 * ## 线程安全
 * 所有回调在主线程执行（View 事件分发线程），无需额外同步。
 *
 * @param steamInput Steam 输入控制器
 * @param injector 输入注入器
 */
class KeyboardMouseMapper(
    private val steamInput: SteamInput,
    private val injector: InputInjector,
    screenWidth: Int = 0,
    screenHeight: Int = 0
) {

    private val TAG = "SteamLikeMapper"

    /**
     * 当前按下的主键集合（用于松开时释放）
     *
     * key = 手柄按钮, value = 主键的 Android KeyCode
     */
    private val pressedMainKeys = mutableMapOf<ControllerButton, Int>()

    /**
     * 当前按下的子命令键集合（用于松开时释放）
     *
     * key = 手柄按钮, value = 子命令的 Android KeyCode 列表
     */
    private val pressedSubKeys = mutableMapOf<ControllerButton, List<Int>>()

    /**
     * 当前按下鼠标按钮的手柄按钮集合
     */
    private val pressedMouseButtons = mutableMapOf<ControllerButton, MouseButton>()

    /**
     * 左摇杆当前按下的方向键集合（WASD映射）
     *
     * 摇杆8方向映射:
     * - 上(W), 下(S), 左(A), 右(D)
     * - 左上(A+W), 右上(D+W), 左下(A+S), 右下(D+S)
     *
     * 每次摇杆移动时计算新方向集合，与旧集合差异发送按键事件。
     */
    private val leftStickPressedKeys = mutableSetOf<Int>()

    /**
     * MouseToggle 当前处于"按下"状态的手柄按钮集合
     *
     * 第一次按下 → 发送 MouseDown，加入集合
     * 第二次按下 → 发送 MouseUp，移出集合
     */
    private val toggledMouseButtons = mutableSetOf<ControllerButton>()

    /**
     * 右摇杆平滑滤波后的 X/Y 值
     *
     * 指数移动平均（EMA）: smoothed = smoothed * α + raw * (1 - α)
     * α 越大越平滑，但延迟增加。
     */
    private var smoothedLookX = 0f
    private var smoothedLookY = 0f

    /**
     * 操作层变化回调
     *
     * 当激活的操作层发生变化时调用，传递当前所有激活层的名称列表。
     * 由 ControllerOverlayService 设置，用于更新悬浮窗 UI。
     */
    var onLayerChanged: ((List<String>) -> Unit)? = null

    /**
     * 启动映射器，注册回调
     *
     * @return true=启动成功
     */
    fun start(): Boolean {
        steamInput.onButtonMapped = { button, isPressed, mapping ->
            handleMapping(button, isPressed, mapping)
        }
        steamInput.onStickMapped = { stick, x, y ->
            handleStick(stick, x, y)
        }
        steamInput.onLayerChanged = { layerName ->
            Log.i(TAG, "Active layer: $layerName")
            // 通知外部监听器，传递当前所有激活层名称列表
            onLayerChanged?.invoke(getActiveLayers())
        }
        Log.i(TAG, "KeyboardMouseMapper started")
        return true
    }

    /**
     * 停止映射器，释放所有按键
     */
    fun stop() {
        injector.releaseAll()
        pressedMainKeys.clear()
        pressedSubKeys.clear()
        pressedMouseButtons.clear()
        leftStickPressedKeys.clear()
        toggledMouseButtons.clear()
        smoothedLookX = 0f
        smoothedLookY = 0f
        Log.i(TAG, "KeyboardMouseMapper stopped")
    }

    // ====================================================================
    // SteamInput 委托方法
    // ====================================================================
    // 以下方法将调用转发到 SteamInput，供 ControllerOverlayService 使用。
    // ====================================================================

    /**
     * 获取当前激活的操作层名称列表
     *
     * @return 激活层名称列表（空列表=仅公共层）
     */
    fun getActiveLayers(): List<String> = steamInput.getActiveLayers().map { it.name }

    /**
     * 处理系统 KeyEvent（按钮按下/释放）
     *
     * 转发到 [SteamInput.dispatchKeyEvent] 进行手柄按键映射。
     *
     * @param event 系统按键事件
     * @return true=已处理
     */
    fun onKeyEvent(event: KeyEvent): Boolean = steamInput.dispatchKeyEvent(event)

    /**
     * 处理系统 MotionEvent（摇杆移动/扳机按压）
     *
     * 转发到 [SteamInput.dispatchGenericMotionEvent] 进行摇杆映射。
     *
     * @param event 系统运动事件
     * @return true=已处理
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean = steamInput.dispatchGenericMotionEvent(event)

    /**
     * 清除所有激活的操作层（回到公共层）
     */
    fun clearAllLayers() = steamInput.deactivateAllLayers()

    /**
     * 按名称激活操作层
     *
     * @param name 操作层名称
     */
    fun activateLayer(name: String) = steamInput.activateLayer(name)

    /**
     * 按名称停用操作层
     *
     * @param name 操作层名称
     */
    fun deactivateLayer(name: String) = steamInput.deactivateLayer(name)

    /**
     * 处理按键映射
     *
     * 根据 [mapping.action] 类型分发到对应的处理方法。
     * 子命令在主键按下/松开时一起注入。
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mapping 按键映射
     */
    private fun handleMapping(button: ControllerButton, isPressed: Boolean, mapping: KeyMapping) {
        when (val action = mapping.action) {
            is MappedAction.KeyboardKey -> {
                handleKeyboardKey(button, isPressed, action.keyCode, mapping.subCommands)
            }
            is MappedAction.MouseClick -> {
                handleMouseClick(button, isPressed, action.button)
            }
            is MappedAction.MouseToggle -> {
                handleMouseToggle(button, isPressed, action.button)
            }
            is MappedAction.SwitchLayer -> {
                // SwitchLayer 已在 SteamInput.handleButtonEvent 中处理
            }
            is MappedAction.MouseMove, is MappedAction.LookAround -> {
                // 摇杆动作，在 handleStick 中处理
            }
        }
    }

    /**
     * 处理键盘按键映射（含子命令）
     *
     * 按下时:
     * 1. 按下主键
     * 2. 依次按下所有子命令键
     *
     * 松开时:
     * 1. 依次松开所有子命令键
     * 2. 松开主键
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mainKeyCode 主键 Android KeyCode
     * @param subCommands 子命令 KeyCode 列表
     */
    private fun handleKeyboardKey(
        button: ControllerButton,
        isPressed: Boolean,
        mainKeyCode: Int,
        subCommands: List<Int>
    ) {
        if (isPressed) {
            // 防重复按下
            if (pressedMainKeys.containsKey(button)) return

            // 1. 按下主键
            injector.sendKeyDown(mainKeyCode)
            pressedMainKeys[button] = mainKeyCode

            // 2. 按下所有子命令键
            val subs = mutableListOf<Int>()
            subCommands.forEach { subKeyCode ->
                injector.sendKeyDown(subKeyCode)
                subs.add(subKeyCode)
            }
            if (subs.isNotEmpty()) {
                pressedSubKeys[button] = subs
            }

            Log.d(TAG, "KeyDown: ${KeyMapping.keyCodeToName(mainKeyCode)}" +
                    if (subCommands.isNotEmpty()) "+${subCommands.map { KeyMapping.keyCodeToName(it) }}" else "")
        } else {
            // 防重复松开
            val main = pressedMainKeys.remove(button) ?: return

            // 1. 松开所有子命令键（逆序）
            pressedSubKeys.remove(button)?.reversed()?.forEach { subKeyCode ->
                injector.sendKeyUp(subKeyCode)
            }

            // 2. 松开主键
            injector.sendKeyUp(main)

            Log.d(TAG, "KeyUp: ${KeyMapping.keyCodeToName(main)}" +
                    if (subCommands.isNotEmpty()) "+${subCommands.map { KeyMapping.keyCodeToName(it) }}" else "")
        }
    }

    /**
     * 处理鼠标点击映射
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mouseButton 鼠标按钮
     */
    private fun handleMouseClick(button: ControllerButton, isPressed: Boolean, mouseButton: MouseButton) {
        if (isPressed) {
            if (pressedMouseButtons.containsKey(button)) return
            injector.sendMouseDown(mouseButton)
            pressedMouseButtons[button] = mouseButton
            Log.d(TAG, "MouseDown: $mouseButton")
        } else {
            val mb = pressedMouseButtons.remove(button) ?: return
            injector.sendMouseUp(mb)
            Log.d(TAG, "MouseUp: $mb")
        }
    }

    /**
     * 处理鼠标长按切换映射
     *
     * 第一次按下 → 发送 MouseDown（不松开）
     * 第二次按下 → 发送 MouseUp（不再按下）
     * 松开手柄键不触发任何操作（保持 toggle 状态）
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放（toggle模式中释放无操作）
     * @param mouseButton 鼠标按钮
     */
    private fun handleMouseToggle(button: ControllerButton, isPressed: Boolean, mouseButton: MouseButton) {
        if (!isPressed) return  // toggle模式：松开手柄键不触发任何操作
        if (toggledMouseButtons.contains(button)) {
            // 第二次按下 → 释放
            injector.sendMouseUp(mouseButton)
            toggledMouseButtons.remove(button)
            Log.d(TAG, "Toggle MouseUp: $mouseButton")
        } else {
            // 第一次按下 → 按下
            injector.sendMouseDown(mouseButton)
            toggledMouseButtons.add(button)
            Log.d(TAG, "Toggle MouseDown: $mouseButton")
        }
    }

    /**
     * 处理摇杆事件
     *
     * 根据摇杆类型和配置决定:
     * - 右摇杆 → 视角控制（LookAround），发送鼠标相对移动
     * - 左摇杆 → 鼠标移动（MouseMove），发送鼠标相对移动（如配置）
     *
     * @param stick 摇杆类型
     * @param x X轴值 (-1.0~1.0)
     * @param y Y轴值 (-1.0~1.0)
     */
    private fun handleStick(stick: ControllerStick, x: Float, y: Float) {
        val settings = steamInput.profile.globalSettings
        when (stick) {
            ControllerStick.RIGHT_STICK -> {
                // 右摇杆 → 视角控制（死区 + 加速曲线 + 平滑滤波）
                // 1. 死区处理：消除中心漂移
                var rx = x
                var ry = y
                val mag = Math.sqrt((rx * rx + ry * ry).toDouble()).toFloat()
                if (mag < settings.deadzone) {
                    rx = 0f
                    ry = 0f
                } else if (mag > 1f) {
                    val scale = 1f / mag
                    rx *= scale
                    ry *= scale
                }
                // 2. 加速曲线：pow(mag, accel) 使轻推更慢、重推更快
                val accel = settings.lookAcceleration.coerceIn(0.5f, 3.0f)
                if (rx != 0f || ry != 0f) {
                    val normMag = Math.sqrt((rx * rx + ry * ry).toDouble()).toFloat()
                    val curve = Math.pow(normMag.toDouble(), accel.toDouble()).toFloat()
                    val scale = if (normMag > 0f) curve / normMag else 0f
                    rx *= scale
                    ry *= scale
                }
                // 3. 平滑滤波（EMA）：减少抖动，让移动更顺滑
                val alpha = settings.lookSmoothing.coerceIn(0f, 0.95f)
                smoothedLookX = smoothedLookX * alpha + rx * (1f - alpha)
                smoothedLookY = smoothedLookY * alpha + ry * (1f - alpha)
                // 4. 发送鼠标移动（基础速度 8px/帧 * 灵敏度）
                val dx = smoothedLookX * settings.lookSensitivity * 8f
                val dy = smoothedLookY * settings.lookSensitivity * 8f
                if (dx != 0f || dy != 0f) {
                    injector.sendMouseMove(dx, dy)
                }
            }
            ControllerStick.LEFT_STICK -> {
                // 左摇杆 → WASD 8方向映射（固定映射，不随操作层变化）
                // 8方向: 上(W)/下(S)/左(A)/右(D)/左上(A+W)/右上(D+W)/左下(A+S)/右下(D+S)
                val threshold = 0.5f
                val newKeys = mutableSetOf<Int>()
                if (x > threshold) newKeys.add(KeyEvent.KEYCODE_D)
                else if (x < -threshold) newKeys.add(KeyEvent.KEYCODE_A)
                if (y > threshold) newKeys.add(KeyEvent.KEYCODE_S)
                else if (y < -threshold) newKeys.add(KeyEvent.KEYCODE_W)

                // 释放不再按下的方向键
                for (key in leftStickPressedKeys) {
                    if (key !in newKeys) injector.sendKeyUp(key)
                }
                // 按下新增的方向键
                for (key in newKeys) {
                    if (key !in leftStickPressedKeys) injector.sendKeyDown(key)
                }
                leftStickPressedKeys.clear()
                leftStickPressedKeys.addAll(newKeys)
            }
            ControllerStick.DPAD_AS_STICK -> {
                // D-Pad 作为摇杆 → 不处理
            }
        }
    }
}
