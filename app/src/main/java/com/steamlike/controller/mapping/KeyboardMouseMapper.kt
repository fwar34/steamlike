package com.steamlike.controller.mapping

import android.os.SystemClock
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
     * key = 手柄按钮, value = 已按下的鼠标按钮
     *
     * 第一次按下 → 发送 MouseDown，加入集合
     * 第二次按下 → 发送 MouseUp，移出集合
     *
     * toggle 是用户主动锁存机制，不随操作层切换自动释放
     * （松开手柄键也不改变状态，见 [handleMouseToggle]）。
     */
    private val toggledMouseButtons = mutableMapOf<ControllerButton, MouseButton>()

    /**
     * 右摇杆最新位置（主线程事件回调写入，发送循环线程读取）
     *
     * 事件驱动方案的缺陷：MotionEvent 到达时间受主线程负载、蓝牙抖动、
     * 系统批处理影响而不均匀——即使位移量按 dt 缩放正确，鼠标包的
     * **时间分布**仍会抖动，游戏内表现为一顿一顿。
     *
     * 业界方案（Steam Input/DS4Windows）是**固定频率刷新循环**：
     * 由稳定时钟每 tick 读取最新位置并发送，包节奏完全均匀。
     * 这里事件回调只负责更新位置状态，实际发送由 [startLookLoop] 消费。
     */
    @Volatile
    private var latestLookX = 0f

    @Volatile
    private var latestLookY = 0f

    /**
     * 右摇杆平滑滤波后的 X/Y 值（仅在发送循环线程内读写）
     *
     * 指数移动平均（EMA），使用**时间常数**：α = 1 - exp(-dt/τ)
     * τ 由 [com.steamlike.controller.core.GlobalSettings.lookSmoothing] 映射（0~45ms），
     * 与事件频率无关，tick 频率变化时平滑效果一致。
     */
    private var smoothedLookX = 0f
    private var smoothedLookY = 0f

    /**
     * 右摇杆固定频率发送循环线程（自校正 tick）
     *
     * 每 [LOOK_TICK_MS] 毫秒读取最新摇杆位置，按实际间隔 dt 计算位移并发送，
     * 位移总量 = 速度 × 实际经过时间（tick 被系统延迟时总量不变，不丢量）。
     * 独立于主线程，主线程 jank 不影响发送节奏。
     */
    private var lookThread: Thread? = null

    @Volatile
    private var lookThreadRunning = false

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
        // 启动右摇杆固定频率发送循环
        startLookLoop()
        Log.i(TAG, "KeyboardMouseMapper started")
        return true
    }

    /**
     * 停止映射器，释放所有按键
     */
    fun stop() {
        stopLookLoop()
        injector.releaseAll()
        pressedMainKeys.clear()
        pressedSubKeys.clear()
        pressedMouseButtons.clear()
        leftStickPressedKeys.clear()
        toggledMouseButtons.clear()
        latestLookX = 0f
        latestLookY = 0f
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
     * **松开事件按"已注入状态"释放**，不按当前层映射分发：
     * 按住 B(鼠标右键) 时切换操作层，激活层可能覆盖 B 的映射（如 B→技能键）。
     * 若按当前映射分发释放会走进 KeyboardKey 分支，而 B 按下时记录在
     * [pressedMouseButtons] 而非 [pressedMainKeys]，导致右键 MouseUp 永远发不出去、
     * 游戏内右键卡死。因此松开统一走 [releaseButtonInjection]。
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mapping 按键映射
     */
    private fun handleMapping(button: ControllerButton, isPressed: Boolean, mapping: KeyMapping) {
        if (!isPressed) {
            // 松开：按按下时记录的状态精确释放（与当前层映射无关）
            releaseButtonInjection(button)
            return
        }
        when (val action = mapping.action) {
            is MappedAction.KeyboardKey -> {
                handleKeyboardKey(button, true, action.keyCode, mapping.subCommands)
            }
            is MappedAction.MouseClick -> {
                handleMouseClick(button, true, action.button)
            }
            is MappedAction.MouseToggle -> {
                handleMouseToggle(button, true, action.button)
            }
            is MappedAction.SwitchLayer -> {
                // SwitchLayer 已在 SteamInput.handleButtonEvent 中处理
            }
            is MappedAction.MouseMove, is MappedAction.LookAround -> {
                // 摇杆动作，在 handleStick 中处理
            }
            is MappedAction.MouseScrollUp -> {
                handleMouseScroll(button, SCROLL_DELTA)
            }
            is MappedAction.MouseScrollDown -> {
                handleMouseScroll(button, -SCROLL_DELTA)
            }
        }
    }

    /**
     * 释放指定手柄按钮已注入的全部键鼠状态
     *
     * 根据按下时记录的状态精确释放（与当前层映射无关）：
     * - 子命令键（逆序松开）
     * - 主键
     * - 鼠标按钮（MouseClick 按下态）
     *
     * 不处理 [MappedAction.MouseToggle]：toggle 是用户主动锁存机制
     * （按一下按住、再按一下释放），松开手柄键不应改变其状态。
     *
     * @param button 手柄按钮
     */
    private fun releaseButtonInjection(button: ControllerButton) {
        var released = false
        // 1. 松开子命令键（逆序）
        pressedSubKeys.remove(button)?.reversed()?.forEach { subKeyCode ->
            injector.sendKeyUp(subKeyCode)
            released = true
        }
        // 2. 松开主键
        pressedMainKeys.remove(button)?.let { mainKeyCode ->
            injector.sendKeyUp(mainKeyCode)
            released = true
        }
        // 3. 松开鼠标按钮（MouseClick 按下态）
        pressedMouseButtons.remove(button)?.let { mouseButton ->
            injector.sendMouseUp(mouseButton)
            released = true
        }
        if (released) {
            Log.d(TAG, "Released button $button injections")
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
        if (toggledMouseButtons.containsKey(button)) {
            // 第二次按下 → 释放
            injector.sendMouseUp(mouseButton)
            toggledMouseButtons.remove(button)
            Log.d(TAG, "Toggle MouseUp: $mouseButton")
        } else {
            // 第一次按下 → 按下
            injector.sendMouseDown(mouseButton)
            toggledMouseButtons[button] = mouseButton
            Log.d(TAG, "Toggle MouseDown: $mouseButton")
        }
    }

    /**
     * 处理鼠标滚轮映射
     *
     * 每次按下发送一次滚轮事件（不需要松开）。
     *
     * @param button 手柄按钮
     * @param delta 滚轮增量（正数=上滚，负数=下滚）
     */
    private fun handleMouseScroll(button: ControllerButton, delta: Float) {
        injector.sendMouseScroll(delta)
        Log.d(TAG, "MouseScroll: delta=$delta")
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
        when (stick) {
            ControllerStick.RIGHT_STICK -> {
                // 右摇杆 → 视角控制（LookAround）
                // 只记录最新位置，实际发送由固定频率循环 [processLookTick] 消费。
                // 事件驱动即时发送时，MotionEvent 到达时间抖动会使鼠标包时间分布不均，
                // 游戏内镜头一顿一顿；固定 tick 发送让包节奏稳定，还原真实鼠标的高频均匀位移。
                latestLookX = x
                latestLookY = y
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

    // ====================================================================
    // 右摇杆固定频率发送循环（业界标准做法: Steam Input / DS4Windows）
    // ====================================================================

    /**
     * 启动右摇杆固定频率发送循环
     *
     * 专用守护线程，自校正 tick：
     * - 每 [LOOK_TICK_MS] 毫秒执行一次 [processLookTick]
     * - 位移按**实际经过时间** dt 计算（tick 被系统调度延迟时总量不变，不丢量）
     * - 循环超时则跳过 sleep 立即进入下一轮（丢帧不堆积延迟）
     */
    private fun startLookLoop() {
        if (lookThreadRunning) return
        lookThreadRunning = true
        lookThread = Thread({
            var lastTickNanos = SystemClock.elapsedRealtimeNanos()
            try {
                while (lookThreadRunning) {
                    val tickStart = SystemClock.elapsedRealtimeNanos()
                    val dt = ((tickStart - lastTickNanos) / 1e9f).coerceIn(0.001f, 0.05f)
                    lastTickNanos = tickStart
                    processLookTick(dt)
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - tickStart) / 1_000_000L
                    val sleepMs = LOOK_TICK_MS - elapsedMs
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs)
                    }
                }
            } catch (e: InterruptedException) {
                // 停止请求
            }
            lookThreadRunning = false
        }, "SteamLike-LookLoop").apply { isDaemon = true }
        lookThread?.start()
    }

    /**
     * 停止右摇杆发送循环，等待线程退出
     */
    private fun stopLookLoop() {
        lookThreadRunning = false
        lookThread?.interrupt()
        lookThread?.join(500)
        lookThread = null
    }

    /**
     * 单个 tick：读取最新摇杆位置，计算并发送鼠标位移
     *
     * 处理链（与业界一致）：幅值钳制 → 加速曲线 → 时间常数 EMA → 位移积分
     * 死区已在 [SteamInput] 侧统一应用。
     *
     * @param dt 实际经过时间（秒），位移 = 速度 × dt
     */
    private fun processLookTick(dt: Float) {
        var rx = latestLookX
        var ry = latestLookY
        val settings = steamInput.profile.globalSettings

        // 幅值钳制（mag>1 缩回单位圆）
        val mag = Math.sqrt((rx * rx + ry * ry).toDouble()).toFloat()
        if (mag > 1f) {
            val scale = 1f / mag
            rx *= scale
            ry *= scale
        }

        // 加速曲线：pow(mag, accel)，轻推更慢、重推更快（精确瞄准/快速转身兼顾）
        val accel = settings.lookAcceleration.coerceIn(0.5f, 3.0f)
        if (rx != 0f || ry != 0f) {
            val normMag = Math.sqrt((rx * rx + ry * ry).toDouble()).toFloat()
            val curve = Math.pow(normMag.toDouble(), accel.toDouble()).toFloat()
            val scale = if (normMag > 0f) curve / normMag else 0f
            rx *= scale
            ry *= scale
        }

        // 时间常数 EMA 平滑（与 tick 频率无关的稳定平滑）
        val tau = settings.lookSmoothing.coerceIn(0f, 0.95f) * LOOK_SMOOTH_TAU_MAX
        val alpha = if (tau <= 0f) 1f else 1f - kotlin.math.exp(-dt / tau)
        smoothedLookX = smoothedLookX * (1f - alpha) + rx * alpha
        smoothedLookY = smoothedLookY * (1f - alpha) + ry * alpha

        // 位移积分：满推 LOOK_SPEED_PX_PER_SEC × 灵敏度 × dt
        // 亚像素部分由注入器余量累积补发（不丢量、不产生脉冲）
        val dx = smoothedLookX * settings.lookSensitivity * LOOK_SPEED_PX_PER_SEC * dt
        val dy = smoothedLookY * settings.lookSensitivity * LOOK_SPEED_PX_PER_SEC * dt
        if (dx != 0f || dy != 0f) {
            injector.sendMouseMove(dx, dy)
        }
    }

    companion object {
        /**
         * 右摇杆满推时的鼠标移动速度（像素/秒）
         *
         * 位移 = 速度 × 实际经过时间，无论 tick/事件频率如何，速度恒定。
         */
        private const val LOOK_SPEED_PX_PER_SEC = 480f

        /**
         * 视角 EMA 平滑时间常数上限（秒）
         *
         * lookSmoothing=0.95 时 τ=45ms，lookSmoothing=0.5（默认）时 τ≈24ms。
         */
        private const val LOOK_SMOOTH_TAU_MAX = 0.048f

        /**
         * 右摇杆发送循环 tick 间隔（毫秒）≈ 125Hz
         *
         * 接近真实鼠标的回报频率（多数鼠标 125~1000Hz），
         * 每 tick 位移 1~4px，配合余量累积实现平滑连续移动。
         */
        private const val LOOK_TICK_MS = 8L

        /** 鼠标滚轮增量（每次按下发送的滚轮量） */
        private const val SCROLL_DELTA = 120f
    }
}
