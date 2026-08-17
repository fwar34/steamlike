package com.steamlike.controller

import android.os.Bundle
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.SteamInput

/**
 * 手柄按键测试 Activity（调试用）
 *
 * ## 用途
 * 模拟器没有真实手柄，此 Activity 通过点击按钮模拟手柄按键的按下/松开事件，
 * 直接触发 [SteamInput.handleButtonEvent]，方便测试操作层切换和按键映射。
 *
 * ## 交互
 * - 触摸按钮 = 按下事件 (isPressed=true)
 * - 松开按钮 = 松开事件 (isPressed=false)
 * - 顶部显示当前激活层名（实时更新）
 *
 * ## 搭配 Windows 测试程序
 * 启动此 Activity 后，可同时在 Windows 端运行 inputbridge_test.exe：
 * ```
 * adb forward tcp:27015 tcp:27015
 * inputbridge_test.exe
 * ```
 * 即可看到 Android 端模拟按键产生的映射输出消息。
 */
class GamepadTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GamepadTestActivity"
    }

    private var steamInput: SteamInput? = null
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        steamInput = LayerEditActivity.steamInputRef
        if (steamInput == null) {
            Toast.makeText(this, "服务未启动或初始化未完成", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        supportActionBar?.apply {
            title = "手柄按键测试"
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 状态显示
        val statusLabel = TextView(this).apply {
            text = "当前激活层:"
            textSize = 14f
        }
        root.addView(statusLabel)

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 8, 0, 16)
            setTextColor(0xFF4CAF50.toInt())
            text = steamInput?.activeLayerName ?: "公共层"
        }
        root.addView(statusText)

        // 监听层变化（叠加在原有回调之上，不破坏 ControllerOverlayService 的层切换监听）
        savedLayerCallback = steamInput?.onLayerChanged
        steamInput?.onLayerChanged = { layerName ->
            runOnUiThread { statusText?.text = layerName }
            savedLayerCallback?.invoke(layerName)
        }

        // 说明
        val hint = TextView(this).apply {
            text = "提示: 按住按钮 = 持续按下，松开 = 释放\n映射的消息会发送到 Windows 测试程序"
            textSize = 12f
            setPadding(0, 0, 0, 16)
            setTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(hint)

        // 按钮网格
        val grid = GridLayout(this).apply {
            columnCount = 4
            rowCount = 5  // 18个按钮 / 4列 = 5行（向上取整）
            useDefaultMargins = true
            alignmentMode = GridLayout.ALIGN_MARGINS
        }

        // 所有可模拟的按钮
        val buttons = listOf(
            ControllerButton.A to "A",
            ControllerButton.B to "B",
            ControllerButton.X to "X",
            ControllerButton.Y to "Y",
            ControllerButton.LEFT_SHOULDER to "LB",
            ControllerButton.RIGHT_SHOULDER to "RB",
            ControllerButton.LEFT_TRIGGER_CLICK to "L2",
            ControllerButton.RIGHT_TRIGGER_CLICK to "R2",
            ControllerButton.LEFT_STICK_CLICK to "L3",
            ControllerButton.RIGHT_STICK_CLICK to "R3",
            ControllerButton.DPAD_UP to "↑",
            ControllerButton.DPAD_DOWN to "↓",
            ControllerButton.DPAD_LEFT to "←",
            ControllerButton.DPAD_RIGHT to "→",
            ControllerButton.MENU to "Menu",
            ControllerButton.OPTIONS to "Options",
            ControllerButton.GUIDE to "Guide",
            ControllerButton.TOUCHPAD_CLICK to "Touchpad"
        )

        for ((button, label) in buttons) {
            val btn = Button(this).apply {
                text = label
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                }

                // 触摸事件: DOWN=按下, UP=松开
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            steamInput?.handleButtonEvent(button, true)
                            v.setBackgroundResource(android.R.color.holo_blue_light)
                            v.performClick()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            steamInput?.handleButtonEvent(button, false)
                            v.setBackgroundResource(0)
                        }
                    }
                    true
                }
            }
            grid.addView(btn)
        }

        root.addView(grid)

        // "释放所有" 按钮
        val releaseAllBtn = Button(this).apply {
            text = "释放所有按键"
            setOnClickListener {
                // 触发所有按钮的松开事件（避免状态残留）
                for ((button, _) in buttons) {
                    steamInput?.handleButtonEvent(button, false)
                }
                Toast.makeText(this@GamepadTestActivity, "已释放所有按键", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(releaseAllBtn)

        // 顶部提示
        val tip = TextView(this).apply {
            text = "\nWindows 端调试:\n  adb forward tcp:27015 tcp:27015\n  inputbridge_test.exe"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 16, 0, 0)
        }
        root.addView(tip)

        setContentView(root)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 恢复原始的 onLayerChanged 回调（ControllerOverlayService 的层切换监听）
        if (savedLayerCallback != null) {
            steamInput?.onLayerChanged = savedLayerCallback
        }
    }

    private var savedLayerCallback: ((String) -> Unit)? = null
}
