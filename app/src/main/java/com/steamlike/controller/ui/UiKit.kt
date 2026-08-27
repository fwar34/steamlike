package com.steamlike.controller.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * App 内 UI 美化工具（纯代码构建的深色卡片风格）
 *
 * 与悬浮窗保持同一视觉语言：深色圆角卡片、圆角按钮
 * （绿=主操作 / 红=危险 / 灰=普通）、圆角输入框、灰阶文字层级。
 */
object UiKit {

    /** 按钮样式 */
    enum class Style { NORMAL, PRIMARY, DANGER }

    // ===== 基础 =====

    /** dp → px */
    fun dp(context: Context, value: Number): Int =
        (value.toFloat() * context.resources.displayMetrics.density).toInt()

    /** 圆角矩形背景 */
    fun rounded(
        context: Context,
        color: Int,
        radiusDp: Number,
        strokeColor: Int? = null,
        strokeWidthDp: Number = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            this.cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    /** 带按下态反馈的圆角按钮背景 */
    fun buttonBackground(context: Context, style: Style): StateListDrawable {
        val (normal, pressed) = when (style) {
            Style.NORMAL -> 0xCC444444.toInt() to 0xCC6E6E6E.toInt()
            Style.PRIMARY -> 0xFF2E7D32.toInt() to 0xFF388E3C.toInt()
            Style.DANGER -> 0xCCB71C1C.toInt() to 0xCCD32F2F.toInt()
        }
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rounded(context, pressed, 10f)
            )
            addState(
                intArrayOf(),
                rounded(context, normal, 10f)
            )
        }
    }

    // ===== 文本 =====

    /** 页面大标题 */
    fun bigTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    /**
     * 自定义标题栏（返回按钮 + 标题）
     *
     * 替代系统 ActionBar。系统 ActionBar 在挖孔屏横屏时会避让挖孔区，
     * 导致标题栏左侧空出避让区（返回箭头/标题不贴边，Android 15+ 强制
     * edge-to-edge 下 shortEdges 也覆盖不了）。自定义标题栏位于页面内容区内，
     * 与内容一样从屏幕左缘开始，彻底规避该问题。
     *
     * @param title 标题文字
     * @param onBack 返回按钮点击回调（通常为 finish()）
     */
    fun titleBar(context: Context, title: String, onBack: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            // 返回按钮（圆角灰底，文字型）
            addView(TextView(context).apply {
                text = "‹ 返回"
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(context, 10), dp(context, 6), dp(context, 12), dp(context, 6))
                background = rounded(context, 0x33444444.toInt(), 8)
                setOnClickListener { onBack() }
            })
            // 标题（占据剩余宽度，左对齐）
            addView(bigTitle(context, title), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(context, 12) })
        }
    }

    /** 区块标题（卡片内） */
    fun sectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFFCCCCCC.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    /** 说明/状态文字 */
    fun caption(context: Context, text: String, color: Int = 0xFFAAAAAA.toInt(), sizeSp: Float = 12f): TextView {
        return TextView(context).apply {
            this.text = text
            this.textSize = sizeSp
            setTextColor(color)
            setLineSpacing(0f, 1.3f)
        }
    }

    /** 字段标签（输入框上方的小标题） */
    fun label(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
        }
    }

    // ===== 容器 =====

    /** 圆角深色卡片容器 */
    fun card(context: Context, paddingDp: Number = 14): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, 0xF22A2A2A.toInt(), 14)
            setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
        }
    }

    /** 卡片间的垂直间距 */
    fun spacer(context: Context, heightDp: Number = 12): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, heightDp)
            )
        }
    }

    // ===== 控件 =====

    /** 圆角功能按钮（绿=主操作 / 红=危险 / 灰=普通） */
    fun button(
        context: Context,
        label: String,
        onClick: () -> Unit,
        style: Style = Style.NORMAL,
        heightDp: Number = 44
    ): Button {
        return Button(context).apply {
            text = label
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            // 不获取焦点：避免点击后 ScrollView 自动滚动到按钮（触屏下点击回调不受影响）
            isFocusable = false
            isFocusableInTouchMode = false
            background = buttonBackground(context, style)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, heightDp)
            )
        }
    }

    /** 输入框（系统默认样式，文字/提示色适配深色背景） */
    fun input(context: Context, hint: String, value: String = ""): EditText {
        return EditText(context).apply {
            this.hint = hint
            setText(value)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x88AAAAAA.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** 开关行（文本 + Switch） */
    fun switchRow(context: Context, labelText: String, checked: Boolean, onChecked: (Boolean) -> Unit): android.widget.Switch {
        return android.widget.Switch(context).apply {
            text = labelText
            isChecked = checked
            setTextColor(0xFFDDDDDD.toInt())
            setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** 根布局深色背景 */
    fun applyDarkBackground(view: View, context: Context) {
        view.background = rounded(context, 0xF21C1C1C.toInt(), 0)
    }
}
