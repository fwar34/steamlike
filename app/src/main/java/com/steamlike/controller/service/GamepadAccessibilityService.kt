package com.steamlike.controller.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：暂停捕获时接收手柄按键，用于恢复捕获（**已弃用**）
 *
 * ## 背景
 * 暂停捕获时 [ControllerOverlayService.pauseCapturing] 会移除 GamepadInputView
 * 焦点窗口，此后手柄事件无法再到达应用，导致"切换捕获"键无法恢复捕获。
 * 本服务曾尝试通过按键过滤（FLAG_REQUEST_FILTER_KEY_EVENTS）在暂停状态下全局
 * 接收手柄按键，转发给 [ControllerOverlayService] 注册的 [onPausedKeyEvent] 回调，
 * 从而让"切换捕获"键在暂停后仍能恢复捕获。
 *
 * ## 现状（已弃用）
 * 实测这台 MIUI 设备**没有授予按键过滤能力**：服务可正常开启，但
 * `dumpsys accessibility` 显示 capabilities=0，系统设置中也没有"按键过滤"开关，
 * [onKeyEvent] 永远不会被调用。因此该方案无法工作。
 *
 * ## 最终方案
 * 暂停捕获 = 真正移除焦点窗口（恢复侧滑返回手势），放弃手柄键恢复捕获。
 * 暂停后只能通过悬浮窗"恢复捕获"按钮或主界面捕获开关恢复（见
 * [ControllerOverlayService.resumeCapturing]）。
 *
 * ## 本类保留原因
 * 保留代码用于：① 在支持按键过滤的设备上验证能力（[hasKeyFiltering]）；
 * ② 万一后续系统授予该能力，逻辑仍可直接启用。新代码不应依赖本类。
 *
 * 捕获进行中（焦点窗口存在）时 [onPausedKeyEvent] 为 null，[onKeyEvent] 直接
 * 返回 false 穿透事件，不干扰正常输入。
 *
 * 按键过滤能力的获取方式：XML 中不声明 flagRequestFilterKeyEvents，而是在
 * [onServiceConnected] 中运行时动态请求该 flag 并重新赋值 serviceInfo，以触发
 * Android 13+ 的系统授权弹窗（或要求用户在服务详情中开启"按键过滤"）。
 * 用户授权与否通过 [hasKeyFiltering] 反映。
 */
class GamepadAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SteamLikeA11y"

        /** 服务是否已连接（用户已在系统无障碍设置中开启） */
        @Volatile
        var isConnected: Boolean = false

        /**
         * 暂停捕获时由 ControllerOverlayService 注册的按键转发回调。
         *
         * 捕获进行中为 null（不拦截按键，穿透到焦点窗口正常处理）。
         * 暂停捕获时由 [ControllerOverlayService.pauseCapturing] 设置，
         * 恢复捕获时由 [ControllerOverlayService.resumeCapturing] 清除。
         */
        @Volatile
        var onPausedKeyEvent: ((KeyEvent) -> Boolean)? = null

        /**
         * 系统是否已授予按键过滤能力（[AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS]）。
         *
         * Android 13+ 中仅开启服务总开关不够，用户还必须单独允许"按键过滤"，
         * 否则 [onKeyEvent] 不会被调用。此字段在服务连接时读取，供暂停捕获时
         * 提示用户是否缺失该授权。
         */
        @Volatile
        var hasKeyFiltering: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        // Android 13+ (API 33+) 的按键过滤被视为敏感能力：即便在 XML 中声明
        // flagRequestFilterKeyEvents，系统也不会自动授予，必须由用户授权。
        // 因此 XML 中不声明该 flag，这里改为运行时动态请求并重新赋值 serviceInfo，
        // 以触发系统的按键过滤授权流程（弹窗或服务详情开关）。
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            Log.i(TAG, "Requested FLAG_REQUEST_FILTER_KEY_EVENTS")
        }
        updateKeyFilteringCapability()
        Log.i(TAG, "Accessibility service connected")
    }

    /** 读取系统授予的按键过滤能力并缓存到 [hasKeyFiltering] */
    private fun updateKeyFilteringCapability() {
        val caps = serviceInfo?.capabilities ?: 0
        hasKeyFiltering = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0
        Log.i(TAG, "Key filtering capability granted: $hasKeyFiltering (caps=$caps)")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isConnected = false
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isConnected = false
        onPausedKeyEvent = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅用于按键过滤，不处理无障碍事件
    }

    override fun onInterrupt() {
        // 忽略
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 无条件日志：无论捕获状态都打，用于确认按键是否到达无障碍服务
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "A11yKey key=${event.keyCode} src=${event.source} dev=${event.deviceId} handler=${onPausedKeyEvent != null}")
        }
        val handler = onPausedKeyEvent
        if (handler == null) {
            // 捕获进行中：穿透，不拦截，让焦点窗口正常处理
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "Paused key: ${event.keyCode} source=${event.source} dev=${event.deviceId}")
        }
        return try {
            handler(event)
        } catch (e: Exception) {
            Log.e(TAG, "onPausedKeyEvent error", e)
            false
        }
    }
}
