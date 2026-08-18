package com.steamlike.controller.core

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Steam风格输入系统主控制器（重构版）
 *
 * ## 架构概述
 *
 * 采用 **公共层 + 操作层** 架构，使用 [ControllerProfile] 管理按键映射。
 *
 * ### 公共层 (commonLayer)
 * - 类型: [OperationLayer]，名称为 "Common"
 * - 始终激活，提供默认按键映射
 * - 所有操作层查询不到的按键回退到此层
 *
 * ### 操作层 (Layer1-Layer10)
 * - 最多 10 个，每个有独立的 [OperationLayer.triggerButton]（仅用于 UI 显示/说明）
 * - 实际层切换由 **公共层的 [MappedAction.SwitchLayer] 映射** 驱动：
 *   按下触发键（如 D-Pad ↑）→ 激活对应层；松开 → 回到公共层
 * - 激活时按键查询优先使用本层映射
 *
 * ### 按键查询顺序
 * ```
 * 用户按下按钮 A
 *      ↓
 * 遍历激活的操作层，查找 buttonMappings[A]
 *      ↓ 找到?
 *      ├─ 是 → 使用该层的映射
 *      └─ 否 → 回退到公共层 commonLayer.buttonMappings[A]
 * ```
 *
 * ## Android 知识点: InputManager
 * [InputManager] 是 Android 系统服务，管理所有输入设备。
 * 通过 [InputManager.getInputDeviceIds] 获取所有设备ID，
 * 通过 [InputManager.registerInputDeviceListener] 监听设备插拔。
 * 手柄设备通过 [InputDevice.SOURCE_GAMEPAD] 或 [InputDevice.SOURCE_JOYSTICK] 识别。
 *
 * ## Android 知识点: Handler & Looper
 * [Handler] 绑定到 [Looper.getMainLooper]（主线程 Looper），用于在主线程执行代码。
 * [mainHandler.postDelayed] 用于定时循环（60fps 更新循环）。
 * 主线程 Looper 由 Android 系统在 [android.app.ActivityThread.main] 中创建。
 *
 * ## 线程安全
 * - [ConcurrentHashMap]: 线程安全的 HashMap，读写无需同步
 * - [CopyOnWriteArrayList]: 写时复制 List，适合读多写少场景
 * - [CopyOnWriteArraySet]: 写时复制 Set，用于 heldButtons
 * - 输入事件在主线程分发（View 事件分发），更新循环也在主线程
 *
 * @param context Android Context，用于获取 InputManager 系统服务
 */
class SteamInput(context: Context) {

    private val appContext = context.applicationContext
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // D-Pad HAT 轴状态跟踪，避免 MotionEvent 重复触发
    // 记录当前 HAT 轴按下的方向（无 = emptySet）
    private val hatState = mutableSetOf<ControllerButton>()
    // 扳机轴（L2/R2）按下状态，避免重复触发
    private var l2Pressed = false
    private var r2Pressed = false
    /**
     * 按键触发的层切换记录
     *
     * key: 触发层切换的按键（如 DPAD_UP）
     * value: 被激活的操作层
     *
     * 按下时如果是 SwitchLayer 映射，记录 button->layer；
     * 松开时检查此映射，停用对应层。这样即使激活层覆盖了该按键的映射，
     * 也能正确停用层（不会执行激活层中该按键的 keyup）。
     */
    private val buttonTriggeredLayers = mutableMapOf<ControllerButton, OperationLayer>()

    /**
     * 控制器配置（公共层 + 操作层 + 全局设置）
     *
     * 由外部通过 [loadProfile] 加载，包含所有按键映射定义。
     */
    @Volatile
    var profile: ControllerProfile = ControllerProfile.createDefault()
        private set

    /**
     * 当前激活的操作层列表
     *
     * 使用 [CopyOnWriteArrayList] 保证遍历安全。
     * 通常只有一个层激活（按住触发键时），松开后清空。
     */
    private val activeLayers = CopyOnWriteArrayList<OperationLayer>()

    /**
     * 当前激活层名称（用于悬浮窗显示）
     */
    @Volatile
    var activeLayerName: String = "Common"
        private set

    /**
     * 已连接的手柄设备列表（按 deviceId 索引）
     */
    private val connectedControllers = ConcurrentHashMap<Int, ControllerDevice>()
    val controllers: Map<Int, ControllerDevice> get() = connectedControllers

    /** 手柄连接回调 */
    var onControllerConnected: ((ControllerDevice) -> Unit)? = null

    /** 手柄断开回调 */
    var onControllerDisconnected: ((ControllerDevice) -> Unit)? = null

    /**
     * 当前按住的所有按钮集合
     *
     * 用于判断操作层触发键是否按住。
     */
    private val heldButtons = CopyOnWriteArraySet<ControllerButton>()

    /**
     * 按钮映射回调
     *
     * 当按钮按下/释放且找到有效映射时调用。
     * 外部（KeyboardMouseMapper）通过此回调执行键盘/鼠标注入。
     *
     * @param button 手柄按钮
     * @param isPressed true=按下, false=释放
     * @param mapping 找到的按键映射
     */
    var onButtonMapped: ((button: ControllerButton, isPressed: Boolean, mapping: KeyMapping) -> Unit)? = null

    /**
     * 摇杆映射回调
     *
     * 当摇杆移动时调用。外部根据映射类型（MouseMove/LookAround）执行不同操作。
     *
     * @param stick 摇杆类型
     * @param x X轴值 (-1.0~1.0)
     * @param y Y轴值 (-1.0~1.0)
     */
    var onStickMapped: ((stick: ControllerStick, x: Float, y: Float) -> Unit)? = null

    /**
     * 操作层切换回调
     *
     * 当激活层发生变化时调用，用于更新悬浮窗 UI。
     */
    var onLayerChanged: ((activeLayerName: String) -> Unit)? = null

    /**
     * 系统输入设备监听器
     *
     * 监听手柄的插入/拔出/变化事件。
     *
     * ## Android 知识点: InputDeviceListener
     * [InputManager.InputDeviceListener] 是回调接口，需在主线程注册（第二个参数）。
     * 回调方法:
     * - [onInputDeviceAdded]: 设备插入时调用（如连接蓝牙手柄）
     * - [onInputDeviceRemoved]: 设备拔出时调用
     * - [onInputDeviceChanged]: 设备配置变化时调用
     */
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = InputDevice.getDevice(deviceId) ?: return
            ControllerDevice.fromInputDevice(device)?.let { controller ->
                connectedControllers[deviceId] = controller
                Log.i(TAG, "Controller connected: ${controller.name} (${controller.controllerType})")
                onControllerConnected?.invoke(controller)
            }
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            connectedControllers.remove(deviceId)?.let { controller ->
                Log.i(TAG, "Controller disconnected: ${controller.name}")
                onControllerDisconnected?.invoke(controller)
            }
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            val device = InputDevice.getDevice(deviceId) ?: return
            ControllerDevice.fromInputDevice(device)?.let { controller ->
                connectedControllers[deviceId] = controller
            }
        }
    }

    /**
     * 初始化: 注册设备监听器、扫描已连接设备
     */
    init {
        inputManager.registerInputDeviceListener(inputDeviceListener, mainHandler)
        InputDevice.getDeviceIds().forEach { deviceId ->
            val device = InputDevice.getDevice(deviceId) ?: return@forEach
            ControllerDevice.fromInputDevice(device)?.let { controller ->
                connectedControllers[deviceId] = controller
            }
        }
    }

    // ====================================================================
    // 配置管理
    // ====================================================================

    /**
     * 加载控制器配置
     *
     * 替换当前的 [profile]，所有按键映射使用新配置。
     * 调用后会停用所有操作层。
     */
    fun loadProfile(newProfile: ControllerProfile) {
        profile = newProfile
        deactivateAllLayers()
        Log.i(TAG, "Profile loaded: ${newProfile.layers.size} layers")
    }

    // ====================================================================
    // 操作层管理
    // ====================================================================

    /**
     * 激活操作层
     *
     * 将层加入激活列表，后续按键查询优先使用此层。
     * 同时更新 [activeLayerName] 并触发 [onLayerChanged] 回调。
     */
    fun activateLayer(layer: OperationLayer) {
        if (!activeLayers.contains(layer)) {
            activeLayers.add(layer)
        }
        activeLayerName = layer.name
        Log.i(TAG, "Layer activated: ${layer.name}")
        onLayerChanged?.invoke(activeLayerName)
    }

    /**
     * 按名称激活操作层
     */
    fun activateLayer(name: String) {
        profile.findLayer(name)?.let { activateLayer(it) }
    }

    /**
     * 停用操作层
     *
     * 从激活列表移除，回到公共层。
     */
    fun deactivateLayer(layer: OperationLayer) {
        if (activeLayers.remove(layer)) {
            activeLayerName = if (activeLayers.isEmpty()) "Common" else activeLayers.last().name
            Log.i(TAG, "Layer deactivated: ${layer.name}, active=${activeLayerName}")
            onLayerChanged?.invoke(activeLayerName)
        }
    }

    /**
     * 按名称停用操作层
     */
    fun deactivateLayer(name: String) {
        profile.findLayer(name)?.let { deactivateLayer(it) }
    }

    /**
     * 停用所有操作层
     */
    fun deactivateAllLayers() {
        activeLayers.clear()
        buttonTriggeredLayers.clear()
        activeLayerName = "Common"
        onLayerChanged?.invoke(activeLayerName)
    }

    /**
     * 检查指定操作层是否激活
     */
    fun isLayerActive(name: String): Boolean = activeLayers.any { it.name == name }

    /**
     * 获取当前激活的操作层列表
     */
    fun getActiveLayers(): List<OperationLayer> = activeLayers.toList()

    // ====================================================================
    // 按键查询
    // ====================================================================

    /**
     * 查找按钮的有效映射（核心方法）
     *
     * 查找顺序:
     * 1. 遍历激活的操作层，查找 buttonMappings[button]
     * 2. 如果没有，回退到公共层 commonLayer.buttonMappings[button]
     *
     * @param button 要查找的按钮
     * @return 找到的映射；未绑定返回 null
     */
    fun getEffectiveMapping(button: ControllerButton): KeyMapping? {
        // 1. 从激活层查找
        for (layer in activeLayers) {
            layer.getMapping(button)?.let { return it }
        }
        // 2. 回退到公共层
        return profile.commonLayer.getMapping(button)
    }

    // ====================================================================
    // 输入分发
    // ====================================================================

    /**
     * 处理系统 KeyEvent（按钮按下/释放）
     *
     * ## Android 知识点: KeyEvent
     * [KeyEvent] 表示键盘/按钮事件，包含:
     * - [KeyEvent.getAction]: ACTION_DOWN 或 ACTION_UP
     * - [KeyEvent.getKeyCode]: 按键代码（如 KEYCODE_BUTTON_A）
     * - [KeyEvent.getDeviceId]: 来源设备ID
     * - [KeyEvent.getRepeatCount]: 长按重复次数（0=首次按下）
     * - [KeyEvent.isFromSource]: 检查事件来源（GAMEPAD/DPAD/JOYSTICK）
     *
     * @param event 系统按键事件
     * @return true=已处理
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) &&
            !event.isFromSource(InputDevice.SOURCE_DPAD) &&
            !event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        ) return false

        val deviceId = event.deviceId
        val controller = connectedControllers[deviceId] ?: return false
        val button = ControllerInputMapper.mapKeyCode(event.keyCode, controller.controllerType) ?: return false
        if (event.repeatCount != 0) return true

        val isPressed = event.action == KeyEvent.ACTION_DOWN
        handleButtonEvent(button, isPressed)
        return true
    }

    /**
     * 处理系统 MotionEvent（摇杆移动/扳机按压）
     *
     * ## Android 知识点: MotionEvent & AXIS
     * [MotionEvent] 包含模拟量轴值:
     * - [MotionEvent.AXIS_X]/[MotionEvent.AXIS_Y]: 左摇杆
     * - [MotionEvent.AXIS_Z]/[MotionEvent.AXIS_RZ]: 右摇杆
     * - [MotionEvent.AXIS_LTRIGGER]/[MotionEvent.AXIS_RTRIGGER]: 扳机
     * - [MotionEvent.getAxisValue]: 获取轴值，范围 -1.0~1.0
     *
     * @param event 系统运动事件
     * @return true=已处理
     */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            !event.isFromSource(InputDevice.SOURCE_GAMEPAD) &&
            !event.isFromSource(InputDevice.SOURCE_DPAD)
        ) return false

        val deviceId = event.deviceId
        val device = event.device ?: return false
        val controller = connectedControllers[deviceId] ?: return false

        val leftStick = ControllerInputMapper.getStickValue(event, ControllerStick.LEFT_STICK, device)
        val rightStick = ControllerInputMapper.getStickValue(event, ControllerStick.RIGHT_STICK, device)

        // 应用死区
        val deadzone = profile.globalSettings.deadzone
        val leftStickDz = leftStick.withDeadzone(deadzone)
        val rightStickDz = rightStick.withDeadzone(deadzone)

        // 通知摇杆事件（始终回调）
        // 原实现仅在"死区外或有居中事件"时回调：摇杆从死区外回中经过死区时
        // 事件被跳过，映射器的 EMA 平滑值会冻结在最后位置，产生拖尾抖动。
        // 始终回调 (0,0) 让平滑值立即收敛到零。
        onStickMapped?.invoke(ControllerStick.LEFT_STICK, leftStickDz.x, leftStickDz.y)
        onStickMapped?.invoke(ControllerStick.RIGHT_STICK, rightStickDz.x, rightStickDz.y)

        // 处理 D-Pad 的 HAT 轴事件（很多手柄的 D-Pad 通过 MotionEvent 而非 KeyEvent 发送）
        handleDpadHatAxis(event)

        // 处理扳机轴 L2/R2（按到底触发 click 事件）
        handleTriggerAxis(event, device)

        return true
    }

    /**
     * 处理 D-Pad 的 HAT 轴（AXIS_HAT_X / AXIS_HAT_Y）
     *
     * ## Android 知识点: HAT 轴
     * 很多手柄的 D-Pad 不发送 KeyEvent.KEYCODE_DPAD_*，而是通过 MotionEvent 的
     * AXIS_HAT_X（-1=左, 0=中, 1=右）和 AXIS_HAT_Y（-1=上, 0=中, 1=下）发送。
     *
     * 本方法将 HAT 轴值转换为按下/释放事件，通过 [handleButtonEvent] 处理层切换和按键映射。
     * 使用 [hatState] 跟踪当前激活方向，避免 MotionEvent 高频触发重复事件。
     *
     * @param event MotionEvent
     */
    private fun handleDpadHatAxis(event: MotionEvent) {
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        // 计算当前激活的方向（|hatX| 或 |hatY| > 0.5 视为按下）
        val upPressed = hatY < -0.5f
        val downPressed = hatY > 0.5f
        val leftPressed = hatX < -0.5f
        val rightPressed = hatX > 0.5f

        // 检测状态变化，避免重复触发
        listOf(
            ControllerButton.DPAD_UP to upPressed,
            ControllerButton.DPAD_DOWN to downPressed,
            ControllerButton.DPAD_LEFT to leftPressed,
            ControllerButton.DPAD_RIGHT to rightPressed
        ).forEach { (button, pressed) ->
            if (pressed && !hatState.contains(button)) {
                hatState.add(button)
                handleButtonEvent(button, isPressed = true)
            } else if (!pressed && hatState.contains(button)) {
                hatState.remove(button)
                handleButtonEvent(button, isPressed = false)
            }
        }
    }

    /**
     * 处理扳机轴 L2/R2（AXIS_LTRIGGER / AXIS_RTRIGGER）
     *
     * ## Android 知识点: 模拟扳机
     * L2/R2 是模拟扳机，行程中发送 MotionEvent.AXIS_LTRIGGER/AXIS_RTRIGGER（值 0.0~1.0）。
     * 按到底（>= [TRIGGER_CLICK_THRESHOLD]）会触发 KEYCODE_BUTTON_L2/R2 KeyEvent，
     * 但部分手柄（尤其是模拟器或非标准驱动）只发 MotionEvent 不发 KeyEvent。
     *
     * 本方法检测扳机轴值，超过阈值时触发 [ControllerButton.LEFT_TRIGGER_CLICK]/
     * [ControllerButton.RIGHT_TRIGGER_CLICK] 按下事件，低于阈值触发释放。
     * 使用 [l2Pressed]/[r2Pressed] 状态避免重复触发。
     *
     * @param event MotionEvent
     * @param device InputDevice（用于检测其他可能轴）
     */
    private fun handleTriggerAxis(event: MotionEvent, device: InputDevice) {
        // L2: 优先 AXIS_LTRIGGER，回退 AXIS_BRAKE
        var l2Value = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        if (l2Value == 0f && device.getMotionRange(MotionEvent.AXIS_BRAKE) != null) {
            l2Value = event.getAxisValue(MotionEvent.AXIS_BRAKE)
        }
        // R2: 优先 AXIS_RTRIGGER，回退 AXIS_GAS
        var r2Value = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        if (r2Value == 0f && device.getMotionRange(MotionEvent.AXIS_GAS) != null) {
            r2Value = event.getAxisValue(MotionEvent.AXIS_GAS)
        }

        // L2 按下/释放检测
        if (l2Value >= TRIGGER_CLICK_THRESHOLD && !l2Pressed) {
            l2Pressed = true
            handleButtonEvent(ControllerButton.LEFT_TRIGGER_CLICK, isPressed = true)
        } else if (l2Value < TRIGGER_CLICK_THRESHOLD && l2Pressed) {
            l2Pressed = false
            handleButtonEvent(ControllerButton.LEFT_TRIGGER_CLICK, isPressed = false)
        }

        // R2 按下/释放检测
        if (r2Value >= TRIGGER_CLICK_THRESHOLD && !r2Pressed) {
            r2Pressed = true
            handleButtonEvent(ControllerButton.RIGHT_TRIGGER_CLICK, isPressed = true)
        } else if (r2Value < TRIGGER_CLICK_THRESHOLD && r2Pressed) {
            r2Pressed = false
            handleButtonEvent(ControllerButton.RIGHT_TRIGGER_CLICK, isPressed = false)
        }
    }

    /**
     * 处理按钮事件
     *
     * 核心逻辑:
     * 1. 更新 heldButtons 集合
     * 2. 松开时：若该按键此前触发过 SwitchLayer 层切换，则停用对应层并返回
     * 3. 查找按钮的有效映射（激活层 → 公共层回退）
     * 4. 如果映射是 SwitchLayer，按下时激活目标层并记录（松开时停用）
     * 5. 否则触发 onButtonMapped 回调
     *
     * @param button 统一按钮编码
     * @param isPressed true=按下, false=释放
     */
    fun handleButtonEvent(button: ControllerButton, isPressed: Boolean) {
        // 更新 heldButtons
        if (isPressed) heldButtons.add(button) else heldButtons.remove(button)

        // 松开时：优先检查该按键是否曾触发过层切换，是则停用对应层
        // 这样即使激活层覆盖了该按键的映射，仍能正确停用层
        // 例如：Common 的 Up->SwitchLayer(L1)，layer1 的 Up->KeyboardKey(C)
        //   按下 Up：激活 layer1（layer1 的 Up 不会执行，因为按下时层刚激活）
        //   松开 Up：buttonTriggeredLayers[Up]=layer1，停用 layer1（不执行 layer1 的 Up->C 的 keyup）
        if (!isPressed) {
            val triggeredLayer = buttonTriggeredLayers.remove(button)
            if (triggeredLayer != null) {
                deactivateLayer(triggeredLayer)
                return
            }
        }

        // 查找有效映射（先查激活层，回退到 Common 层）
        val mapping = getEffectiveMapping(button) ?: return

        // 处理 SwitchLayer 动作（通过按键映射切换层）
        when (val action = mapping.action) {
            is MappedAction.SwitchLayer -> {
                val targetLayer = profile.findLayer(action.layerName)
                if (targetLayer != null) {
                    if (isPressed) {
                        // 激活目标层，并记录该按键触发了层切换（用于松开时停用）
                        activateLayer(targetLayer)
                        buttonTriggeredLayers[button] = targetLayer
                    }
                    // 松开的处理在函数开头已处理（buttonTriggeredLayers.remove）
                }
            }
            else -> {
                // 键盘/鼠标动作 → 通知外部注入
                onButtonMapped?.invoke(button, isPressed, mapping)
            }
        }
    }

    /**
     * 获取当前按住的按钮集合
     */
    fun getHeldButtons(): Set<ControllerButton> = heldButtons.toSet()

    /**
     * 获取第一个已连接手柄的设备ID
     */
    fun getFirstControllerId(): Int? = connectedControllers.keys.firstOrNull()

    /**
     * 触发手柄震动
     *
     * ## Android 知识点: VibrationEffect
     * API 26+ 使用 [android.os.VibrationEffect.createOneShot] 创建震动效果。
     * 需要手柄设备支持震动（[ControllerDevice.supportsVibration]）。
     *
     * @param deviceId 设备ID
     * @param durationMs 震动时长（毫秒）
     * @param amplitude 震动强度 (0-255)
     */
    fun vibrate(deviceId: Int, durationMs: Long = 200, amplitude: Int = 255) {
        val controller = connectedControllers[deviceId] ?: return
        if (!controller.supportsVibration) return
        val vibrator = controller.inputDevice.vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    /**
     * 销毁控制器，释放资源
     */
    fun destroy() {
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        deactivateAllLayers()
    }

    companion object {
        private const val TAG = "SteamLikeInput"

        /**
         * 扳机键"按到底"判定阈值
         *
         * 模拟扳机轴值 0.0~1.0，达到此阈值视为按下（触发 click 事件）
         */
        private const val TRIGGER_CLICK_THRESHOLD = 0.5f
    }
}
