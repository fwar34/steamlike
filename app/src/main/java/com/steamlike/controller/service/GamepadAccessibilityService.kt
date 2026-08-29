package com.steamlike.controller.service  // 包声明：本文件位于 com.steamlike.controller.service 包

import android.accessibilityservice.AccessibilityService  // 导入无障碍服务基类
import android.accessibilityservice.AccessibilityServiceInfo  // 导入无障碍服务信息类
import android.util.Log  // 导入日志工具类
import android.view.KeyEvent  // 导入按键事件类
import android.view.accessibility.AccessibilityEvent  // 导入无障碍事件类

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
class GamepadAccessibilityService : AccessibilityService() {  // class=类声明；继承 AccessibilityService() 无障碍服务基类，实现按键过滤

    companion object {  // companion object=伴生对象，存放类级别的静态成员
        private const val TAG = "SteamLikeA11y"  // const val=编译期常量；日志标签

        /** 服务是否已连接（用户已在系统无障碍设置中开启） */
        @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
        var isConnected: Boolean = false  // var=可变变量；服务是否已连接，默认 false

        /**
         * 暂停捕获时由 ControllerOverlayService 注册的按键转发回调。
         *
         * 捕获进行中为 null（不拦截按键，穿透到焦点窗口正常处理）。
         * 暂停捕获时由 [ControllerOverlayService.pauseCapturing] 设置，
         * 恢复捕获时由 [ControllerOverlayService.resumeCapturing] 清除。
         */
        @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
        // var=可变变量；?=可空类型；((KeyEvent)->Boolean)?=可空的按键回调 lambda（参数 KeyEvent 返回 Boolean），默认 null
        var onPausedKeyEvent: ((KeyEvent) -> Boolean)? = null

        /**
         * 系统是否已授予按键过滤能力（[AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS]）。
         *
         * Android 13+ 中仅开启服务总开关不够，用户还必须单独允许"按键过滤"，
         * 否则 [onKeyEvent] 不会被调用。此字段在服务连接时读取，供暂停捕获时
         * 提示用户是否缺失该授权。
         */
        @Volatile  // 注解：标记字段为 volatile，保证多线程可见性
        var hasKeyFiltering: Boolean = false  // var=可变变量；系统是否已授予按键过滤能力
    }  // 结束 companion object 伴生对象

    override fun onServiceConnected() {  // override=覆写父类方法；fun=函数声明；服务连接成功时的回调
        super.onServiceConnected()  // 调用父类的 onServiceConnected 实现
        isConnected = true  // 标记服务已连接
        // Android 13+ (API 33+) 的按键过滤被视为敏感能力：即便在 XML 中声明
        // flagRequestFilterKeyEvents，系统也不会自动授予，必须由用户授权。
        // 因此 XML 中不声明该 flag，这里改为运行时动态请求并重新赋值 serviceInfo，
        // 以触发系统的按键过滤授权流程（弹窗或服务详情开关）。
        val info = serviceInfo  // val=只读变量；获取当前服务配置信息
        if (info != null) {  // if 判断：配置信息非空才处理
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS  // 按位或：在原有 flags 上追加按键过滤 flag
            serviceInfo = info  // 重新赋值 serviceInfo，触发系统按键过滤授权流程
            Log.i(TAG, "Requested FLAG_REQUEST_FILTER_KEY_EVENTS")  // 打印已请求按键过滤 flag 的日志
        }  // 结束 if 块
        updateKeyFilteringCapability()  // 调用私有方法，更新按键过滤能力缓存
        Log.i(TAG, "Accessibility service connected")  // 打印服务已连接日志
    }  // 结束 onServiceConnected 函数

    /** 读取系统授予的按键过滤能力并缓存到 [hasKeyFiltering] */
    private fun updateKeyFilteringCapability() {  // private=私有方法；fun=函数声明；读取系统授予的按键过滤能力
        val caps = serviceInfo?.capabilities ?: 0  // val=只读变量；?.=安全调用（serviceInfo 为空得 null）；?:=空值合并（null 时取 0）
        // 按位与判断是否具备按键过滤能力，结果存入 hasKeyFiltering
        hasKeyFiltering = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0
        Log.i(TAG, "Key filtering capability granted: $hasKeyFiltering (caps=$caps)")  // 字符串模板 "$var"=插值变量；打印能力与 caps 值
    }  // 结束 updateKeyFilteringCapability 函数

    override fun onUnbind(intent: android.content.Intent?): Boolean {  // override=覆写父类方法；fun=函数声明；服务被解绑时回调，?=可空参数，返回 Boolean
        isConnected = false  // 标记服务已断开
        Log.i(TAG, "Accessibility service unbound")  // 打印服务解绑日志
        return super.onUnbind(intent)  // 调用父类实现并返回其结果
    }  // 结束 onUnbind 函数

    override fun onDestroy() {  // override=覆写父类方法；fun=函数声明；服务销毁时回调
        isConnected = false  // 标记服务已断开
        onPausedKeyEvent = null  // 清空按键转发回调
        Log.i(TAG, "Accessibility service destroyed")  // 打印服务销毁日志
        super.onDestroy()  // 调用父类实现
    }  // 结束 onDestroy 函数

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {  // override=覆写父类方法；fun=函数声明；无障碍事件回调，?=可空参数
        // 仅用于按键过滤，不处理无障碍事件
    }  // 结束 onAccessibilityEvent 函数

    override fun onInterrupt() {  // override=覆写父类方法；fun=函数声明；服务被系统中断时回调
        // 忽略
    }  // 结束 onInterrupt 函数

    override fun onKeyEvent(event: KeyEvent): Boolean {  // override=覆写父类方法；fun=函数声明；按键事件回调，返回 true 表示已拦截
        // 无条件日志：无论捕获状态都打，用于确认按键是否到达无障碍服务
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {  // if 判断：按下动作且非长按重复时才记录日志
            // 字符串模板 "$var"=插值变量；打印按键码、事件来源、设备与是否有回调
            Log.i(TAG, "A11yKey key=${event.keyCode} src=${event.source} dev=${event.deviceId} handler=${onPausedKeyEvent != null}")
        }  // 结束 if 块
        val handler = onPausedKeyEvent  // val=只读变量；取出当前按键转发回调
        if (handler == null) {  // if 判断：无回调（捕获进行中）时直接穿透
            // 捕获进行中：穿透，不拦截，让焦点窗口正常处理
            return false  // 返回 false，不拦截按键
        }  // 结束 if 块
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {  // if 判断：按下且非重复时打印暂停态按键日志
            Log.i(TAG, "Paused key: ${event.keyCode} source=${event.source} dev=${event.deviceId}")  // 字符串模板 "$var"=插值变量；打印暂停状态下的按键信息
        }  // 结束 if 块
        return try {  // try=异常捕获；把回调调用放入 try 块执行
            handler(event)  // 调用 lambda 回调：把按键事件交给暂停时注册的处理函数
        } catch (e: Exception) {  // catch=捕获异常分支
            Log.e(TAG, "onPausedKeyEvent error", e)  // 打印回调执行异常日志
            false  // 异常时返回 false，不拦截
        }  // 结束 try-catch 表达式
    }  // 结束 onKeyEvent 函数
}  // 结束 GamepadAccessibilityService 类
