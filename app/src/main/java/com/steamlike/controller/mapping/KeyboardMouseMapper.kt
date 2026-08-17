package com.steamlike.controller.mapping

import android.view.KeyEvent
import android.view.MotionEvent
import com.steamlike.controller.core.*
import com.steamlike.controller.injection.InputInjector
import com.steamlike.controller.injection.MouseButton

/**
 * 手柄 -> 键盘/鼠标映射器（无操作集版本）
 *
 * ## 职责
 * 将手柄输入转换为 WoW 游戏所需的键盘/鼠标事件，通过 TCP 桥接发送给 Windows 客户端。
 * 同时管理10个操作层的切换和运行时按键映射配置。
 *
 * ## 架构
 * - 公共层(commonLayer): 始终激活, 定义所有动作和默认按键绑定
 * - 10个操作层: 可叠加切换, 每个层继承公共层并可覆盖按键映射
 * - 组合键(ChordBinding): 同一按钮在不同修饰键下触发不同动作（参考Steam子指令）
 *
 * ## 核心数据流
 * ```
 * 手柄硬件
 *      ↓
 * GamepadInputView (焦点窗口捕获)
 *      ↓
 * SteamInput.dispatchKeyEvent / dispatchGenericMotionEvent
 *      ↓
 * onInterceptButton 回调 ← 快捷键拦截(LB+按键 切换层)
 *      ↓ 未拦截?
 * getEffectiveButtonBinding ← 层路由 + 组合键匹配
 *      ↓
 * commonLayer.buttonActions[name].onPressed ← 触发动作回调
 *      ↓
 * InputInjector.sendKeyPress/sendMouseClick ← 注入键鼠事件
 *      ↓
 * TCP桥接 → Windows SendInput → WoW游戏响应
 * ```
 *
 * ## 快捷键
 * - LB + D-Pad Up/Down/Left/Right → 切换前4个操作层(Combat/Mount/Aim/Loot)
 * - LB + A/B/X/Y/L3/R3           → 切换后6个操作层(Stealth/Fishing/PvP/Raid/Travel/Custom)
 * - LB + GUIDE                    → 清除所有层
 *
 * ## 组合键（参考Steam子指令）
 * 通过 commonLayer.addChordBinding() 定义，例如:
 * - A + RB → TargetEnemy（覆盖A的默认动作Jump）
 * - D-Pad Up + L3 → Slot5（覆盖D-Pad Up的默认动作Slot1）
 *
 * @param steamInput Steam输入控制器
 * @param injector 输入注入器（TCP桥接实现）
 * @param screenWidth 屏幕宽度（像素）
 * @param screenHeight 屏幕高度（像素）
 */
class KeyboardMouseMapper(
    private val steamInput: SteamInput,
    private val injector: InputInjector,
    private val screenWidth: Int = 1920,
    private val screenHeight: Int = 1080
) {
    /** WoW操作层配置（公共层 + 10个操作层） */
    private var wowConfig: WoWConfig? = null

    /** 当前按下的WASD键集合（用于摇杆→键盘映射的状态管理） */
    private val wasdKeys = mutableSetOf<Int>()

    /** 鼠标右键是否按下（用于右摇杆视角控制时自动按住右键） */
    private var rightMouseDown = false

    /** 右摇杆视角移动灵敏度（像素/帧） */
    var lookSensitivity = 15f

    /** 光标移动速度（像素/帧，用于Cursor摇杆模式） */
    var cursorSpeed = 8f

    /** 操作层变化时的回调（通知UI更新显示） */
    var onLayerChanged: ((List<String>) -> Unit)? = null

    /** LB(左肩键)是否按住（用于快捷键检测） */
    private var lbHeld = false

    /**
     * 启动映射器
     *
     * 流程:
     * 1. 检查注入器是否可用
     * 2. 初始化WoW操作层配置（公共层 + 10个操作层）
     * 3. 设置公共层中所有动作的回调（按键→键鼠注入）
     * 4. 设置按钮事件拦截器（LB+按钮 切换操作层）
     * 5. 通知初始状态
     *
     * 注意: 手柄事件由 GamepadInputView（焦点窗口）捕获并转发到 SteamInput，
     * 本方法不再启动任何后台读取线程。
     *
     * @return true=启动成功
     */
    fun start(): Boolean {
        if (!injector.isAvailable()) return false

        // 1. 初始化操作层配置
        wowConfig = WoWActionSets.setup(steamInput)

        // 2. 设置公共层动作回调
        setupCommonLayerCallbacks()

        // 3. 设置按钮事件拦截器（处理 LB+按钮 快捷键）
        steamInput.onInterceptButton = { button, pressed ->
            interceptButton(button, pressed)
        }

        // 4. 通知初始状态
        onLayerChanged?.invoke(getActiveLayers())
        return true
    }

    /**
     * 停止映射器
     *
     * - 释放所有按下的键鼠（防止按键卡住）
     * - 销毁SteamInput
     */
    fun stop() {
        steamInput.onInterceptButton = null
        cleanupAllInputs()
        steamInput.destroy()
    }

    // ====================================================================
    // 输入事件转发（供 GamepadInputView 调用）
    // ====================================================================

    /**
     * 处理 KeyEvent（按钮按下/释放）
     *
     * 由 GamepadInputView 的 onKeyEvent 回调调用，转发到 SteamInput。
     * SteamInput 内部会进行按键映射、拦截器检查、组合键匹配和动作分发。
     *
     * @param event 系统按键事件
     * @return true=已处理
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        return steamInput.dispatchKeyEvent(event)
    }

    /**
     * 处理 MotionEvent（摇杆移动/扳机按压）
     *
     * 由 GamepadInputView 的 onGenericMotion 回调调用，转发到 SteamInput。
     *
     * @param event 系统运动事件
     * @return true=已处理
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return steamInput.dispatchGenericMotionEvent(event)
    }

    // ====================================================================
    // 快捷键拦截
    // ====================================================================

    /**
     * 快捷键拦截器
     *
     * 作为 SteamInput.onInterceptButton 回调，在按键映射后、动作分发前调用。
     *
     * 拦截逻辑:
     * 1. LB本身不拦截，仅记录其状态。LB按下时先释放所有当前按住的按钮（防止切换层时按键卡住）
     * 2. LB按住期间:
     *    - D-Pad 上/下/左/右 → 切换前4个操作层(Combat/Mount/Aim/Loot)
     *    - A/B/X/Y/L3/R3 → 切换后6个操作层(Stealth/Fishing/PvP/Raid/Travel/Custom)
     *    - GUIDE → 清除所有层
     *    - 其他按钮 → 也拦截（防止LB组合时误触游戏功能）
     * 3. LB未按住时所有按钮正常分发
     *
     * @param button 手柄按钮
     * @param pressed 是否按下
     * @return true=已拦截（不继续分发），false=正常分发
     */
    private fun interceptButton(button: ControllerButton, pressed: Boolean): Boolean {
        // 追踪LB状态
        if (button == ControllerButton.LEFT_SHOULDER) {
            if (pressed) {
                // LB按下: 释放所有当前按住的按钮，防止切换层时按键卡住
                steamInput.getHeldButtons().forEach { btn ->
                    if (btn != ControllerButton.LEFT_SHOULDER) {
                        steamInput.handleButtonEvent(btn, false)
                    }
                }
            }
            lbHeld = pressed
            return false  // LB本身不拦截，正常分发
        }

        // LB按住时: D-Pad/A/B/X/Y/L3/R3用于切换层, GUIDE清除所有层
        if (lbHeld) {
            if (pressed) {
                when (button) {
                    // 前4个操作层
                    ControllerButton.DPAD_UP -> toggleLayer("Combat")
                    ControllerButton.DPAD_DOWN -> toggleLayer("Mount")
                    ControllerButton.DPAD_LEFT -> toggleLayer("Aim")
                    ControllerButton.DPAD_RIGHT -> toggleLayer("Loot")
                    // 后6个操作层
                    ControllerButton.A -> toggleLayer("Stealth")
                    ControllerButton.B -> toggleLayer("Fishing")
                    ControllerButton.X -> toggleLayer("PvP")
                    ControllerButton.Y -> toggleLayer("Raid")
                    ControllerButton.LEFT_STICK_CLICK -> toggleLayer("Travel")
                    ControllerButton.RIGHT_STICK_CLICK -> toggleLayer("Custom")
                    // 清除所有层
                    ControllerButton.GUIDE -> clearAllLayers()
                    else -> return false  // 其他按钮正常分发
                }
                return true  // 已拦截
            }
            return true  // LB按住时所有按钮释放事件也拦截
        }

        return false  // 正常分发
    }

    // ====================================================================
    // 公共层回调设置
    // ====================================================================

    /**
     * 设置公共层中所有动作的回调
     *
     * 将每个动作的 onPressed/onReleased/onValueChanged 回调设置为
     * 对应的键盘/鼠标注入操作。
     *
     * 回调中的动作名称需与 [WoWActionSets.createCommonLayer] 中定义的一致。
     */
    private fun setupCommonLayerCallbacks() {
        val c = steamInput.commonLayer

        // ===== 基础按钮: A/B/X/Y/LB/RB/MENU/OPTIONS/GUIDE/L3/R3 =====
        c.buttonActions["Jump"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_SPACE) }
        c.buttonActions["Interact"]?.onPressed = { injector.sendMouseClick(MouseButton.RIGHT) }
        c.buttonActions["Attack"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_T) }
        c.buttonActions["Inventory"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_B) }
        c.buttonActions["TargetEnemy"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_TAB) }
        c.buttonActions["FaceTarget"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_F) }
        c.buttonActions["Menu"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_ESCAPE) }
        c.buttonActions["Chat"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_ENTER) }
        c.buttonActions["AutoRun"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_NUM_LOCK) }
        c.buttonActions["Reply"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_R) }
        c.buttonActions["Map"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_M) }

        // ===== 快捷栏: 数字键1-0, -, = =====
        c.buttonActions["Slot1"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_1) }
        c.buttonActions["Slot2"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_2) }
        c.buttonActions["Slot3"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_3) }
        c.buttonActions["Slot4"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_4) }
        c.buttonActions["Slot5"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_5) }
        c.buttonActions["Slot6"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_6) }
        c.buttonActions["Slot7"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_7) }
        c.buttonActions["Slot8"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_8) }
        c.buttonActions["Slot9"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_9) }
        c.buttonActions["Slot0"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_0) }
        c.buttonActions["SlotDash"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_MINUS) }
        c.buttonActions["SlotEqual"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_EQUALS) }

        // ===== 拾取相关动作 =====
        c.buttonActions["Loot"]?.onPressed = { injector.sendMouseClick(MouseButton.RIGHT) }
        c.buttonActions["CloseLoot"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_ESCAPE) }
        c.buttonActions["TakeAll"]?.onPressed = { injector.sendMouseClick(MouseButton.LEFT) }

        // ===== 特殊动作（默认映射，用户可自行修改） =====
        c.buttonActions["Stealth"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_F) }
        c.buttonActions["Fish"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_F) }
        c.buttonActions["MountUp"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_F) }
        c.buttonActions["Mark1"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_1) }
        c.buttonActions["Mark2"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_2) }
        c.buttonActions["Mark3"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_3) }
        c.buttonActions["Mark4"]?.onPressed = { injector.sendKeyPress(KeyEvent.KEYCODE_4) }

        // ===== 摇杆: 左摇杆=移动(WASD), 右摇杆=视角(鼠标), 第三摇杆=光标 =====
        c.stickActions["Move"]?.onValueChanged = { v -> handleMoveStick(v) }
        c.stickActions["Look"]?.onValueChanged = { v -> handleLookStick(v) }
        c.stickActions["Cursor"]?.onValueChanged = { v -> handleCursorStick(v) }

        // ===== 扳机: LT=Shift修饰键, RT=鼠标左键(施法) =====
        c.triggerActions["Modifier"]?.let { trigger ->
            trigger.onPressed = { injector.sendKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT) }
            trigger.onReleased = { injector.sendKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT) }
        }
        c.triggerActions["Cast"]?.let { trigger ->
            trigger.onPressed = { injector.sendMouseDown(MouseButton.LEFT) }
            trigger.onReleased = { injector.sendMouseUp(MouseButton.LEFT) }
        }
    }

    // ====================================================================
    // 操作层切换 API
    // ====================================================================

    /**
     * 切换指定操作层的激活状态（开→关 / 关→开）
     *
     * 激活时震动反馈50ms（强度120），停用时震动30ms（强度80）。
     * 切换后通知 [onLayerChanged] 回调更新UI。
     *
     * @param name 操作层名称（如 "Combat"）
     */
    fun toggleLayer(name: String) {
        val layer = wowConfig?.layers?.get(name) ?: return
        if (steamInput.isLayerActive(name)) {
            steamInput.deactivateActionSetLayer(layer)
            vibrate(30, 80)
        } else {
            steamInput.activateActionSetLayer(layer)
            vibrate(50, 120)
        }
        onLayerChanged?.invoke(getActiveLayers())
    }

    /**
     * 激活指定操作层（如已激活则不重复操作）
     * @param name 操作层名称
     */
    fun activateLayer(name: String) {
        val layer = wowConfig?.layers?.get(name) ?: return
        if (!steamInput.isLayerActive(name)) {
            android.util.Log.i("SteamLikeMapper", "Layer activated: $name")
            steamInput.activateActionSetLayer(layer)
            vibrate(50, 120)
            onLayerChanged?.invoke(getActiveLayers())
        }
    }

    /**
     * 关闭指定操作层（如未激活则不操作）
     * @param name 操作层名称
     */
    fun deactivateLayer(name: String) {
        val layer = wowConfig?.layers?.get(name) ?: return
        if (steamInput.isLayerActive(name)) {
            android.util.Log.i("SteamLikeMapper", "Layer deactivated: $name")
            steamInput.deactivateActionSetLayer(layer)
            vibrate(30, 80)
            onLayerChanged?.invoke(getActiveLayers())
        }
    }

    /**
     * 清除所有操作层（清空栈）
     *
     * 清空后所有按键回退到公共层默认绑定。
     * 震动反馈60ms（强度150）。
     */
    fun clearAllLayers() {
        if (steamInput.getActiveLayers().isNotEmpty()) {
            steamInput.deactivateAllLayers()
            vibrate(60, 150)
            onLayerChanged?.invoke(emptyList())
        }
    }

    // ====================================================================
    // 运行时设置层按键映射 API
    // ====================================================================

    /**
     * 为指定操作层设置按钮映射（覆盖公共层绑定）
     *
     * 运行时动态修改层的绑定，无需重新创建层。
     *
     * @param layerName 操作层名称
     * @param button 手柄按钮
     * @param actionName 要映射到的动作名称（需在公共层中已定义）
     *
     * 示例:
     * ```
     * // 将 Custom 层的 A 键映射到 Slot5
     * mapper.setLayerButtonBinding("Custom", ControllerButton.A, "Slot5")
     * ```
     */
    fun setLayerButtonBinding(layerName: String, button: ControllerButton, actionName: String) {
        steamInput.setLayerButtonBinding(layerName, button, actionName)
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
        steamInput.clearLayerButtonBinding(layerName, button)
    }

    /**
     * 清除指定操作层的所有覆盖
     *
     * 完全恢复继承公共层，该层变为"空层"。
     *
     * @param layerName 操作层名称
     */
    fun clearLayerAllOverrides(layerName: String) {
        steamInput.clearLayerAllOverrides(layerName)
    }

    // ====================================================================
    // 状态查询 API
    // ====================================================================

    /**
     * 获取当前激活的操作层显示名称列表（栈底到栈顶顺序）
     * @return 显示名称列表
     */
    fun getActiveLayers(): List<String> {
        return steamInput.getActiveLayers().map { it.displayName }
    }

    /**
     * 检查指定操作层是否激活
     * @param name 操作层名称
     * @return true=已激活
     */
    fun isLayerActive(name: String): Boolean {
        return steamInput.isLayerActive(name)
    }

    /**
     * 获取所有操作层名称（名称→显示名）
     * @return 配对列表
     */
    fun getAllLayers(): List<Pair<String, String>> {
        return WoWActionSets.LAYER_NAMES
    }

    /**
     * 获取当前按住的所有按钮集合（用于UI显示组合键状态）
     * @return 按住的按钮集合
     */
    fun getHeldButtons(): Set<ControllerButton> {
        return steamInput.getHeldButtons()
    }

    // ====================================================================
    // 摇杆处理
    // ====================================================================

    /**
     * 处理移动摇杆: 左摇杆 → WASD键
     *
     * 将摇杆的2D向量转换为4个方向键(W/A/S/D)的按下/释放。
     * 阈值0.3: 摇杆偏移超过30%才触发对应方向键。
     *
     * 坐标系: Y轴上为负、下为正
     * - Y < -0.3 → W（上）
     * - Y > 0.3  → S（下）
     * - X < -0.3 → A（左）
     * - X > 0.3  → D（右）
     *
     * @param v 摇杆向量（经过死区和响应曲线处理）
     */
    private fun handleMoveStick(v: Vector2) {
        updateWasdKey(KeyEvent.KEYCODE_W, v.y < -0.3f)
        updateWasdKey(KeyEvent.KEYCODE_S, v.y > 0.3f)
        updateWasdKey(KeyEvent.KEYCODE_A, v.x < -0.3f)
        updateWasdKey(KeyEvent.KEYCODE_D, v.x > 0.3f)
    }

    /**
     * 更新单个WASD键的按下/释放状态
     *
     * 使用状态集合 [wasdKeys] 跟踪当前按下的键，防止重复触发。
     *
     * @param keyCode 键盘KeyCode
     * @param shouldPress 是否应该按下
     */
    private fun updateWasdKey(keyCode: Int, shouldPress: Boolean) {
        if (shouldPress && keyCode !in wasdKeys) {
            // 需要按下且当前未按下 → 按下
            wasdKeys.add(keyCode)
            injector.sendKeyDown(keyCode)
        } else if (!shouldPress && keyCode in wasdKeys) {
            // 需要释放且当前已按下 → 释放
            wasdKeys.remove(keyCode)
            injector.sendKeyUp(keyCode)
        }
    }

    /**
     * 释放所有WASD键（在停止或切换层时调用，防止按键卡住）
     */
    private fun releaseWasdKeys() {
        wasdKeys.toList().forEach { injector.sendKeyUp(it) }
        wasdKeys.clear()
    }

    /**
     * 处理视角摇杆: 右摇杆 → 鼠标移动 + 右键按住
     *
     * WoW中视角控制需要按住鼠标右键拖动，因此:
     * - 摇杆有输入时: 自动按住鼠标右键，并发送鼠标移动事件
     * - 摇杆归零时: 释放鼠标右键
     *
     * @param v 摇杆向量（乘以 [lookSensitivity] 得到移动像素数）
     */
    private fun handleLookStick(v: Vector2) {
        if (v.magnitude > 0.1f) {
            // 有输入: 按住右键并移动鼠标
            if (!rightMouseDown) {
                injector.sendMouseDown(MouseButton.RIGHT)
                rightMouseDown = true
            }
            injector.sendMouseMove(v.x * lookSensitivity, v.y * lookSensitivity)
        } else {
            // 无输入: 释放右键
            if (rightMouseDown) {
                injector.sendMouseUp(MouseButton.RIGHT)
                rightMouseDown = false
            }
        }
    }

    /**
     * 处理光标摇杆: 摇杆 → 鼠标移动（不按住任何键）
     *
     * 用于需要移动鼠标光标但不按住右键的场景（如菜单导航）。
     *
     * @param v 摇杆向量（乘以 [cursorSpeed] 得到移动像素数）
     */
    private fun handleCursorStick(v: Vector2) {
        if (v.magnitude > 0.1f) {
            injector.sendMouseMove(v.x * cursorSpeed, v.y * cursorSpeed)
        }
    }

    // ====================================================================
    // 工具方法
    // ====================================================================

    /**
     * 清理所有输入状态
     *
     * 在停止映射器或切换层时调用，释放所有按下的键鼠，防止按键卡住。
     */
    private fun cleanupAllInputs() {
        releaseWasdKeys()
        if (rightMouseDown) {
            injector.sendMouseUp(MouseButton.RIGHT)
            rightMouseDown = false
        }
        injector.releaseAll()
    }

    /**
     * 触发手柄震动反馈
     *
     * @param durationMs 震动时长（毫秒）
     * @param amplitude 震动强度 (0-255)
     */
    private fun vibrate(durationMs: Long = 50, amplitude: Int = 100) {
        steamInput.getFirstControllerId()?.let { id ->
            steamInput.vibrate(id, durationMs, amplitude)
        }
    }
}
