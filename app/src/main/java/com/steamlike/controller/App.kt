package com.steamlike.controller

import android.app.Application

/**
 * Application 入口
 *
 * ## 职责
 * 应用级初始化入口。当前为空实现，预留扩展点。
 *
 * ## 声明位置
 * 在 [AndroidManifest.xml](../AndroidManifest.xml) 中通过 `<application android:name=".App">` 声明。
 * Android 系统在应用进程启动时会先创建 Application 实例，再创建任何 Activity / Service。
 *
 * ## 生命周期
 * - **onCreate()**: 应用进程启动时调用（先于所有组件）
 * - **onTerminate()**: 应用进程结束时调用（仅模拟器可靠，真机不保证调用）
 *
 * ## 未来扩展点
 * 此处可添加:
 * - 全局异常处理器（`Thread.setDefaultUncaughtExceptionHandler`）
 * - 全局日志初始化
 * - 全局配置加载
 * - 性能监控初始化
 *
 * 当前未使用任何全局状态，所有初始化都在 [MainActivity] 或 [service.ControllerOverlayService] 中完成。
 */
class App : Application()
