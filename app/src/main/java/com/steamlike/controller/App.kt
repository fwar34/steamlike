package com.steamlike.controller

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application 入口
 *
 * ## 职责
 * 应用级初始化入口：
 * - 强制深色主题：界面（状态栏/列表/控件/对话框）统一深色，
 *   避免系统浅色模式下状态栏变白、列表文字变黑
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
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 强制深色主题（不随系统浅色模式变化）：
        // 应用界面为自绘深色卡片风格，系统浅色模式下若跟随 DayNight 会导致
        // 状态栏变白、ListView/控件文字变黑。强制深色后全界面统一。
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
