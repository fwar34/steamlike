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
 * - 最多 10 个，每个有独立的 [OperationLayer.triggerButton]
 * - 按住触发键激活该层，松开回公共层
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
            !event.isFromSource(InputDevice.SOURCE_GAMEPAD)
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

        // 通知摇杆事件
        if (leftStickDz.magnitude > 0f || leftStick.magnitude == 0f) {
            onStickMapped?.invoke(ControllerStick.LEFT_STICK, leftStickDz.x, leftStickDz.y)
        }
        if (rightStickDz.magnitude > 0f || rightStick.magnitude == 0f) {
            onStickMapped?.invoke(ControllerStick.RIGHT_STICK, rightStickDz.x, rightStickDz.y)
        }

        return true
    }

    /**
     * 处理按钮事件
     *
     * 核心逻辑:
     * 1. 更新 heldButtons 集合
     * 2. 检查是否是操作层触发键（按下激活层，松开停用层）
     * 3. 查找按钮的有效映射
     * 4. 如果映射是 SwitchLayer，执行层切换
     * 5. 否则触发 onButtonMapped 回调
     *
     * @param button 统一按钮编码
     * @param isPressed true=按下, false=释放
     */
    fun handleButtonEvent(button: ControllerButton, isPressed: Boolean) {
        // 更新 heldButtons
        if (isPressed) heldButtons.add(button) else heldButtons.remove(button)

        // 检查是否是操作层触发键
        val layerByTrigger = profile.findLayerByTrigger(button)
        if (layerByTrigger != null) {
            if (isPressed) {
                activateLayer(layerByTrigger)
            } else {
                deactivateLayer(layerByTrigger)
            }
            return  // 触发键本身不执行映射动作
        }

        // 查找有效映射
        val mapping = getEffectiveMapping(button) ?: return

        // 处理 SwitchLayer 动作（通过按键映射切换层）
        when (val action = mapping.action) {
            is MappedAction.SwitchLayer -> {
                if (isPressed) {
                    profile.findLayer(action.layerName)?.let { activateLayer(it) }
                } else {
                    profile.findLayer(action.layerName)?.let { deactivateLayer(it) }
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
    }
}
