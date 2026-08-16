package com.steamlike.controller.core

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Steam风格输入系统主控制器（无操作集版本）
 *
 * ## 架构概述
 *
 * 本控制器采用 **公共层 + 操作层** 的两层架构，灵感来自 Steam Input API 的 Action Set Layer 机制，
 * 但移除了 Action Set（操作集）的概念，简化为单一公共层 + 多个可叠加操作层。
 *
 * ### 公共层 (commonLayer)
 * - 类型: [ActionSet]，始终处于激活状态
 * - 职责: 定义所有可能的输入动作（按钮/摇杆/扳机）及其默认按键绑定
 * - 特点: 所有操作层默认继承公共层的全部绑定，无需重复定义
 *
 * ### 操作层 (ActionSetLayer)
 * - 数量: 默认10个（Combat/Mount/Aim/Loot/Stealth/Fishing/PvP/Raid/Travel/Custom）
 * - 职责: 在公共层基础上做增量覆盖，可单独修改任意按键映射
 * - 特点: 可同时激活多个层，按激活顺序形成栈，栈顶优先级最高
 *
 * ### 按键查找顺序
 * ```
 * 用户按下按钮 A
 *      ↓
 * 从栈顶到栈底遍历活跃操作层，查找 buttonBindingOverrides[A]
 *      ↓ 找到?
 *      ├─ 是 → 使用该层的覆盖绑定（如 Combat 层将 A 映射到 "Slot5"）
 *      └─ 否 → 回退到公共层 commonLayer.buttonBindings[A]（如 "Jump"）
 * ```
 *
 * ### 数据流
 * ```
 * 手柄硬件 → GamepadInputView(焦点窗口) → SteamInput.dispatchKeyEvent()
 *      → ControllerInputMapper 统一按键编码
 *      → getEffectiveButtonBinding() 查找当前有效绑定
 *      → commonLayer.buttonActions[actionName] 获取动作
 *      → 应用操作层属性覆盖（死区/灵敏度等）
 *      → 触发 action.onPressed / onReleased 回调
 *      → KeyboardMouseMapper 中的回调注入键盘/鼠标事件到系统
 * ```
 *
 * ## 线程安全
 * - 使用 [ConcurrentHashMap] 和 [CopyOnWriteArrayList] 保证并发安全
 * - 输入事件可能在多个线程触发（焦点窗口事件分发线程、主线程）
 * - 更新循环在主线程执行（通过 [Handler]）
 *
 * @param context Android Context，用于获取 InputManager 系统服务
 */
class SteamInput(context: Context) {

    private val appContext = context.applicationContext
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // ====================================================================
    // 公共层: 始终激活的基础动作和绑定
    // ====================================================================

    /**
     * 公共层 - 始终激活的基础按键映射容器
     *
     * 包含:
     * - 所有按钮/摇杆/扳机动作的定义（buttonActions/stickActions/triggerActions）
     * - 默认按键绑定（buttonBindings/stickBindings/triggerBindings）
     *
     * 操作层不拥有自己的动作定义，而是复用公共层中的动作，
     * 仅通过 buttonBindingOverrides 来改变"哪个按钮触发哪个动作"。
     */
    val commonLayer = ActionSet("__common__", "公共层")

    // ====================================================================
    // 操作层管理
    // ====================================================================

    /**
     * 所有已注册的操作层（按名称索引）
     *
     * 通过 [createActionSetLayer] 注册。操作层本身不包含动作定义，
     * 只包含对公共层绑定的覆盖（overrides）。
     */
    val actionSetLayers = ConcurrentHashMap<String, ActionSetLayer>()

    /**
     * 当前激活的操作层栈
     *
     * - 使用 [CopyOnWriteArrayList] 保证遍历时的线程安全
     * - 栈底是先激活的层，栈顶是后激活的层
     * - 按键查找时从栈顶（后激活）到栈底（先激活）遍历
     * - 后激活的层优先级更高，可覆盖先激活层的绑定
     */
    private val activeLayerStack = CopyOnWriteArrayList<ActionSetLayer>()

    // ====================================================================
    // 设备管理
    // ====================================================================

    /**
     * 已连接的手柄设备列表（按 deviceId 索引）
     *
     * 通过 [InputManager.InputDeviceListener] 自动监听设备的插入和拔出。
     */
    private val connectedControllers = ConcurrentHashMap<Int, ControllerDevice>()
    val controllers: Map<Int, ControllerDevice> get() = connectedControllers

    /** 手柄连接时的回调（外部可设置） */
    var onControllerConnected: ((ControllerDevice) -> Unit)? = null

    /** 手柄断开时的回调（外部可设置） */
    var onControllerDisconnected: ((ControllerDevice) -> Unit)? = null

    // ====================================================================
    // 当前输入状态
    // ====================================================================

    /**
     * 每个设备的当前输入状态快照
     *
     * 存储按钮按下状态、摇杆位置、扳机值，用于状态查询和差量计算。
     */
    private val currentStates = ConcurrentHashMap<Int, ControllerState>()

    /**
     * 当前按住的所有按钮集合（跨设备合并）
     *
     * 用于组合键(Chord Binding)匹配: 当按钮A按下时，检查其他按住的按钮
     * 来决定触发哪个组合键动作。
     */
    private val heldButtons = java.util.concurrent.CopyOnWriteArraySet<ControllerButton>()

    /**
     * 按钮事件拦截器回调
     *
     * 在按键映射完成后、动作分发前调用。
     * 返回 true = 拦截（跳过正常动作分发，但仍更新 heldButtons）。
     * 返回 false = 正常分发。
     *
     * 用于实现 LB+按钮 切换操作层等系统级快捷键。
     */
    var onInterceptButton: ((ControllerButton, Boolean) -> Boolean)? = null

    /** 上次更新循环的时间戳，用于计算帧间隔 delta */
    private var lastUpdateTime = System.currentTimeMillis()

    /**
     * 系统输入设备监听器
     *
     * 监听手柄的插入/拔出/变化事件，自动维护 [connectedControllers]。
     */
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = InputDevice.getDevice(deviceId) ?: return
            ControllerDevice.fromInputDevice(device)?.let { controller ->
                connectedControllers[deviceId] = controller
                onControllerConnected?.invoke(controller)
            }
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            connectedControllers.remove(deviceId)?.let { controller ->
                currentStates.remove(deviceId)
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
     * 初始化: 注册设备监听器、扫描已连接设备、启动更新循环
     */
    init {
        inputManager.registerInputDeviceListener(inputDeviceListener, mainHandler)
        // 扫描当前已连接的输入设备
        InputDevice.getDeviceIds().forEach { deviceId ->
            val device = InputDevice.getDevice(deviceId) ?: return@forEach
            ControllerDevice.fromInputDevice(device)?.let { controller ->
                connectedControllers[deviceId] = controller
            }
        }
        startUpdateLoop()
    }

    // ====================================================================
    // 公共层绑定配置 API
    // ====================================================================

    /**
     * 绑定按钮到公共层的动作
     *
     * @param button 手柄按钮（如 [ControllerButton.A]）
     * @param actionName 动作名称（需先通过 commonLayer.addButtonAction 注册）
     *
     * 示例:
     * ```
     * steamInput.commonLayer.addButtonAction("Jump") {}
     * steamInput.bindButton(ControllerButton.A, "Jump")  // A键 → Jump动作
     * ```
     */
    fun bindButton(button: ControllerButton, actionName: String) {
        commonLayer.buttonBindings[button] = actionName
    }

    /**
     * 绑定摇杆到公共层的动作
     *
     * @param stick 手柄摇杆（如 [ControllerStick.LEFT_STICK]）
     * @param actionName 动作名称（需先通过 commonLayer.addStickAction 注册）
     */
    fun bindStick(stick: ControllerStick, actionName: String) {
        commonLayer.stickBindings[stick] = actionName
    }

    /**
     * 绑定扳机到公共层的动作
     *
     * @param trigger 手柄扳机（如 [ControllerTrigger.LEFT_TRIGGER]）
     * @param actionName 动作名称（需先通过 commonLayer.addTriggerAction 注册）
     */
    fun bindTrigger(trigger: ControllerTrigger, actionName: String) {
        commonLayer.triggerBindings[trigger] = actionName
    }

    // ====================================================================
    // 操作层管理 API
    // ====================================================================

    /**
     * 创建并注册一个操作层
     *
     * @param name 层的唯一标识名（如 "Combat"）
     * @param displayName 显示名称（如 "战斗模式"）
     * @param block 配置块，可在其中调用 overrideButtonBinding/overrideStick 等
     * @return 创建的 [ActionSetLayer] 实例
     *
     * 示例:
     * ```
     * steamInput.createActionSetLayer("Combat", "战斗模式") {
     *     overrideButtonBinding(ControllerButton.A, "Slot5")
     *     overrideStick("Look") { deadzone = 0.2f }
     * }
     * ```
     */
    fun createActionSetLayer(
        name: String,
        displayName: String = name,
        block: ActionSetLayer.() -> Unit = {}
    ): ActionSetLayer {
        return ActionSetLayer(name, displayName).apply(block).also { actionSetLayers[name] = it }
    }

    /**
     * 激活操作层（加入栈顶）
     *
     * - 如果该层已在栈中，先移除再加入栈顶（重新排序）
     * - 激活后该层的覆盖优先级最高（栈顶）
     * - 更新所有层的 stackPosition
     *
     * @param layer 要激活的操作层
     */
    fun activateActionSetLayer(layer: ActionSetLayer) {
        // 如果层已在栈中，先移除（避免重复）
        if (activeLayerStack.contains(layer)) {
            activeLayerStack.remove(layer)
        }
        // 加入栈顶
        activeLayerStack.add(layer)
        // 更新所有层的栈位置索引
        activeLayerStack.forEachIndexed { idx, l -> l.stackPosition = idx }
        // 触发层的激活回调
        layer.activate(activeLayerStack.size - 1)
    }

    /**
     * 按名称激活操作层
     * @param name 操作层名称
     */
    fun activateActionSetLayer(name: String) {
        actionSetLayers[name]?.let { activateActionSetLayer(it) }
    }

    /**
     * 停用操作层（从栈中移除）
     *
     * @param layer 要停用的操作层
     */
    fun deactivateActionSetLayer(layer: ActionSetLayer) {
        if (activeLayerStack.remove(layer)) {
            layer.deactivate()
            // 重新计算剩余层的栈位置
            activeLayerStack.forEachIndexed { idx, l -> l.stackPosition = idx }
        }
    }

    /**
     * 按名称停用操作层
     * @param name 操作层名称
     */
    fun deactivateActionSetLayer(name: String) {
        actionSetLayers[name]?.let { deactivateActionSetLayer(it) }
    }

    /**
     * 停用所有操作层（清空栈）
     *
     * 清空后所有按键回退到公共层的默认绑定。
     */
    fun deactivateAllLayers() {
        activeLayerStack.toList().forEach { it.deactivate() }
        activeLayerStack.clear()
    }

    /**
     * 获取当前激活的操作层列表（栈底到栈顶顺序）
     * @return 操作层列表的副本
     */
    fun getActiveLayers(): List<ActionSetLayer> = activeLayerStack.toList()

    /**
     * 检查指定操作层是否处于激活状态
     * @param name 操作层名称
     * @return true=已激活
     */
    fun isLayerActive(name: String): Boolean =
        activeLayerStack.any { it.name == name }

    // ====================================================================
    // 运行时设置层按键映射 API
    // ====================================================================

    /**
     * 为指定操作层设置/覆盖某个按钮的按键映射
     *
     * 运行时动态修改层的绑定，无需重新创建层。
     *
     * @param layerName 操作层名称
     * @param button 手柄按钮
     * @param actionName 要映射到的动作名称
     *
     * 示例:
     * ```
     * // 将 Custom 层的 A 键映射到 Slot5 动作
     * steamInput.setLayerButtonBinding("Custom", ControllerButton.A, "Slot5")
     * ```
     */
    fun setLayerButtonBinding(layerName: String, button: ControllerButton, actionName: String) {
        actionSetLayers[layerName]?.overrideButtonBinding(button, actionName)
    }

    /**
     * 清除指定操作层的某个按钮覆盖
     *
     * 清除后该按钮回退到公共层的默认绑定。
     *
     * @param layerName 操作层名称
     * @param button 要清除覆盖的按钮
     */
    fun clearLayerButtonBinding(layerName: String, button: ControllerButton) {
        actionSetLayers[layerName]?.buttonBindingOverrides?.remove(button)
    }

    /**
     * 清除指定操作层的所有覆盖
     *
     * 完全恢复继承公共层，该层变为"空层"。
     *
     * @param layerName 操作层名称
     */
    fun clearLayerAllOverrides(layerName: String) {
        actionSetLayers[layerName]?.let { layer ->
            layer.buttonBindingOverrides.clear()
            layer.triggerOverrides.clear()
            layer.stickOverrides.clear()
        }
    }

    // ====================================================================
    // 输入分发 - KeyEvent（按钮按下/释放）
    // ====================================================================

    /**
     * 处理系统 KeyEvent（按钮按下/释放）
     *
     * 此方法应在 Activity.dispatchKeyEvent 中调用，用于接收系统转发的手柄按键事件。
     * 在悬浮窗服务场景下，由于无法直接接收 KeyEvent，改用 [handleButtonEvent]。
     *
     * @param event 系统按键事件
     * @return true=已处理该事件
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 过滤: 只处理来自手柄/D-Pad/摇杆的事件
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) &&
            !event.isFromSource(InputDevice.SOURCE_DPAD) &&
            !event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        ) return false

        val deviceId = event.deviceId
        val controller = connectedControllers[deviceId] ?: return false
        // 将 Android KeyCode 映射为统一的 ControllerButton
        val button = ControllerInputMapper.mapKeyCode(event.keyCode, controller.controllerType) ?: return false
        // 忽略重复按键（长按产生的 repeat）
        if (event.repeatCount != 0) return true

        val isPressed = event.action == KeyEvent.ACTION_DOWN

        // 拦截器回调: 允许外部拦截按钮事件（如 LB+按钮 切换操作层）
        if (onInterceptButton?.invoke(button, isPressed) == true) {
            // 被拦截: 仅更新 heldButtons，跳过动作分发
            if (isPressed) heldButtons.add(button) else heldButtons.remove(button)
            return true
        }

        // 更新设备状态快照
        updateButtonState(deviceId, button, isPressed, event.eventTime)
        // 分发到动作
        dispatchButton(button, isPressed)
        return true
    }

    /**
     * 处理系统 MotionEvent（摇杆移动/扳机按压）
     *
     * 此方法应在 Activity.dispatchGenericMotionEvent 中调用。
     *
     * @param event 系统运动事件
     * @return true=已处理该事件
     */
    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            !event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        ) return false

        val deviceId = event.deviceId
        val device = event.device ?: return false
        val controller = connectedControllers[deviceId] ?: return false

        // 读取所有摇杆和扳机的原始值
        val leftStick = ControllerInputMapper.getStickValue(event, ControllerStick.LEFT_STICK, device)
        val rightStick = ControllerInputMapper.getStickValue(event, ControllerStick.RIGHT_STICK, device)
        val dpadStick = ControllerInputMapper.getStickValue(event, ControllerStick.DPAD_AS_STICK, device)
        val leftTrigger = ControllerInputMapper.getTriggerValue(event, ControllerTrigger.LEFT_TRIGGER, device)
        val rightTrigger = ControllerInputMapper.getTriggerValue(event, ControllerTrigger.RIGHT_TRIGGER, device)

        // 合并到状态快照
        val prev = currentStates[deviceId]
        val state = ControllerState(
            deviceId = deviceId, timestamp = event.eventTime,
            buttons = prev?.buttons ?: emptyMap(),
            sticks = mapOf(
                ControllerStick.LEFT_STICK to leftStick,
                ControllerStick.RIGHT_STICK to rightStick,
                ControllerStick.DPAD_AS_STICK to dpadStick
            ),
            triggers = mapOf(
                ControllerTrigger.LEFT_TRIGGER to leftTrigger,
                ControllerTrigger.RIGHT_TRIGGER to rightTrigger
            )
        )
        currentStates[deviceId] = state
        dispatchAnalog(state, controller)
        return true
    }

    // ====================================================================
    // 程序化按钮事件入口（用于拦截器释放按钮等内部调用）
    // ====================================================================

    /**
     * 处理按钮事件（程序化调用，跳过拦截器）
     *
     * 用于内部需要直接触发按钮动作的场景（如拦截器中释放所有按住的按钮）。
     * 不经过 [onInterceptButton] 回调，直接调用 [dispatchButton]。
     *
     * @param button 统一按钮编码
     * @param isPressed true=按下, false=释放
     */
    fun handleButtonEvent(button: ControllerButton, isPressed: Boolean) {
        dispatchButton(button, isPressed)
    }

    /**
     * 处理摇杆事件（程序化调用）
     *
     * @param stick 摇杆类型
     * @param x X轴值 (-1.0 ~ 1.0)
     * @param y Y轴值 (-1.0 ~ 1.0)
     */
    fun handleStickEvent(stick: ControllerStick, x: Float, y: Float) {
        commonLayer.stickBindings[stick]?.let { actionName ->
            commonLayer.stickActions[actionName]?.let { action ->
                action.rawValue = Vector2(x, y)
            }
        }
    }

    /**
     * 处理扳机事件（程序化调用）
     *
     * @param trigger 扳机类型
     * @param value 扳机值 (0.0 ~ 1.0)
     */
    fun handleTriggerEvent(trigger: ControllerTrigger, value: Float) {
        commonLayer.triggerBindings[trigger]?.let { actionName ->
            commonLayer.triggerActions[actionName]?.let { action ->
                action.currentValue = value
            }
        }
    }

    // ====================================================================
    // 内部实现
    // ====================================================================

    /**
     * 更新设备状态快照中的按钮状态
     */
    private fun updateButtonState(deviceId: Int, button: ControllerButton, isPressed: Boolean, time: Long) {
        val prev = currentStates[deviceId]
        val newButtons = (prev?.buttons?.toMutableMap() ?: mutableMapOf()).also { it[button] = isPressed }
        currentStates[deviceId] = ControllerState(
            deviceId = deviceId, timestamp = time,
            buttons = newButtons,
            sticks = prev?.sticks ?: emptyMap(),
            triggers = prev?.triggers ?: emptyMap()
        )
    }

    /**
     * 查找按钮的有效绑定（核心方法）
     *
     * 查找顺序:
     * 1. 检查公共层的组合键绑定(Chord Bindings)，找出所有 chord 是当前按住按钮集合子集的绑定，
     *    选择 chord 最大的绑定（最具体的匹配优先）。仅检查 chord 非空的绑定（有修饰键）。
     * 2. 从栈顶到栈底遍历活跃操作层，查找 buttonBindingOverrides[button]
     * 3. 如果所有层都没有覆盖该按钮，回退到公共层 commonLayer.buttonBindings[button]
     *
     * ## 组合键匹配示例
     * 假设公共层定义:
     * - chordBindings: A+RB→"TargetEnemy", A+RT→"Potion", D-PadUp+L3→"Slot5"
     * - buttonBindings: A→"Jump", D-PadUp→"Slot1"
     *
     * 当 A 按下时 heldButtons={A,RB}:
     * - chord {RB} ⊆ {A,RB} → 匹配 → 返回 "TargetEnemy"
     *
     * 当 A 按下时 heldButtons={A}（无修饰键）:
     * - 无 chord 匹配 → 回退到 buttonBindings → 返回 "Jump"
     *
     * @param button 要查找的按钮
     * @return 绑定的动作名称，null=未绑定任何动作
     */
    private fun getEffectiveButtonBinding(button: ControllerButton): String? {
        // 1. 检查组合键绑定（仅非空chord，即有修饰键的情况）
        var bestChord: ChordBinding? = null
        for (cb in commonLayer.chordBindings) {
            if (cb.button != button) continue
            if (cb.chord.isEmpty()) continue  // 跳过空chord（默认绑定由buttonBindings处理）
            if (!cb.matches(heldButtons)) continue
            // 选择 chordSize 最大的（最具体的匹配）
            if (bestChord == null || cb.chordSize > bestChord.chordSize) {
                bestChord = cb
            }
        }
        if (bestChord != null) return bestChord.actionName

        // 2. 从栈顶到栈底查找层覆盖（后激活的层优先级更高）
        for (i in activeLayerStack.indices.reversed()) {
            activeLayerStack[i].buttonBindingOverrides[button]?.let { return it }
        }
        // 3. 回退到公共层默认绑定
        return commonLayer.buttonBindings[button]
    }

    /**
     * 分发按钮事件到对应的动作
     *
     * 流程:
     * 1. 更新 heldButtons 集合（用于组合键匹配）
     * 2. 查找有效绑定 → 获取动作名称（含组合键匹配）
     * 3. 从公共层获取动作实例
     * 4. 应用操作层的动作属性覆盖（如修改 heldTimeMs 重置等）
     * 5. 触发 onPressed / onReleased 回调
     *
     * @param button 按下的按钮
     * @param isPressed true=按下, false=释放
     */
    private fun dispatchButton(button: ControllerButton, isPressed: Boolean) {
        // 更新 heldButtons（用于组合键匹配）
        if (isPressed) {
            heldButtons.add(button)
        } else {
            heldButtons.remove(button)
        }

        val actionName = getEffectiveButtonBinding(button) ?: return
        val action = commonLayer.buttonActions[actionName] ?: return

        // 按钮动作目前无可覆盖的属性，此处预留扩展点
        // 如需覆盖按钮属性（如长按阈值），可在此应用

        if (isPressed) {
            // 按下: 防重复触发（仅在未按下状态时触发 onPressed）
            if (!action.isPressed) {
                action.isPressed = true
                action.heldTimeMs = 0
                action.onPressed?.invoke()
            }
        } else {
            // 释放: 防重复触发（仅在按下状态时触发 onReleased）
            if (action.isPressed) {
                action.isPressed = false
                action.onReleased?.invoke()
                action.heldTimeMs = 0
            }
        }
    }

    /**
     * 分发模拟量事件（摇杆/扳机）到对应的动作
     *
     * 流程:
     * 1. 遍历公共层的所有摇杆绑定，更新对应动作的 rawValue
     * 2. 如果设备没有左摇杆，尝试用 D-Pad 作为移动输入
     * 3. 遍历公共层的所有扳机绑定，更新对应动作的 currentValue
     * 4. 每次更新前应用操作层的属性覆盖（死区/响应曲线等）
     *
     * @param state 当前设备的输入状态快照
     * @param controller 当前设备信息
     */
    private fun dispatchAnalog(state: ControllerState, controller: ControllerDevice) {
        // ===== 摇杆分发 =====
        commonLayer.stickBindings.forEach { (stick, actionName) ->
            commonLayer.stickActions[actionName]?.let { action ->
                // 应用层的摇杆属性覆盖（如死区、响应曲线）
                for (layer in activeLayerStack) {
                    layer.stickOverrides[actionName]?.applyTo(action)
                }
                // 更新摇杆原始值（响应曲线在 updateAll 中处理）
                action.rawValue = state.getStick(stick)
            }
        }

        // ===== D-Pad 作为移动输入的兼容处理 =====
        // 如果设备没有左摇杆或未绑定左摇杆，尝试用 D-Pad 作为移动
        if (!controller.hasLeftStick || commonLayer.stickBindings[ControllerStick.LEFT_STICK] == null) {
            commonLayer.stickActions["Move"]?.let { action ->
                val dpad = state.getStick(ControllerStick.DPAD_AS_STICK)
                if (dpad.magnitude > 0f) action.rawValue = dpad
            }
        }

        // ===== 扳机分发 =====
        commonLayer.triggerBindings.forEach { (trigger, actionName) ->
            commonLayer.triggerActions[actionName]?.let { action ->
                // 应用层的扳机属性覆盖（如按压阈值）
                for (layer in activeLayerStack) {
                    layer.triggerOverrides[actionName]?.applyTo(action)
                }
                action.currentValue = state.getTrigger(trigger)
            }
        }
    }

    /**
     * 启动60fps更新循环
     *
     * 每16ms（约60fps）执行一次 [ActionSet.updateAll]，负责:
     * - 累加按钮按住时长（heldTimeMs）
     * - 触发按钮的 onUpdate 回调（用于检测长按）
     * - 检测扳机跨越按压阈值，触发 onPressed/onReleased
     * - 应用摇杆死区和响应曲线，触发 onValueChanged 回调
     */
    private fun startUpdateLoop() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val delta = now - lastUpdateTime
                lastUpdateTime = now
                commonLayer.updateAll(delta)
                mainHandler.postDelayed(this, 16)  // ~60fps
            }
        }, 16)
    }

    /**
     * 获取指定设备的当前输入状态
     * @param deviceId 设备ID
     * @return 状态快照，null=设备未连接
     */
    fun getControllerState(deviceId: Int): ControllerState? = currentStates[deviceId]

    /**
     * 获取当前按住的所有按钮集合（快照副本）
     *
     * 用于组合键匹配状态查询和UI显示。
     *
     * @return 按住的按钮集合副本
     */
    fun getHeldButtons(): Set<ControllerButton> = heldButtons.toSet()

    /**
     * 获取第一个已连接手柄的设备ID
     * @return 设备ID，null=无手柄连接
     */
    fun getFirstControllerId(): Int? = connectedControllers.keys.firstOrNull()

    /**
     * 触发手柄震动
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
     *
     * - 注销输入设备监听器
     * - 停用所有操作层
     * - 移除更新循环的回调
     */
    fun destroy() {
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        deactivateAllLayers()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
