package com.steamlike.controller.ui // 语法：package=包声明，声明本文件所属包

import android.content.Context // 语法：import=导入类，Context 提供应用上下文环境
import android.graphics.Typeface // 语法：import=导入类，Typeface 用于设置字体样式
import android.graphics.drawable.GradientDrawable // 语法：import=导入类，GradientDrawable=渐变圆角背景
import android.graphics.drawable.StateListDrawable // 语法：import=导入类，StateListDrawable=按按压状态切换的背景
import android.view.View // 语法：import=导入类，View=所有控件基类
import android.widget.Button // 语法：import=导入类，Button=按钮控件
import android.widget.EditText // 语法：import=导入类，EditText=文本输入框控件
import android.widget.LinearLayout // 语法：import=导入类，LinearLayout=线性布局容器
import android.widget.TextView // 语法：import=导入类，TextView=文本显示控件

/**
 * App 内 UI 美化工具（纯代码构建的深色卡片风格）
 *
 * 与悬浮窗保持同一视觉语言：深色圆角卡片、圆角按钮
 * （绿=主操作 / 红=危险 / 灰=普通）、圆角输入框、灰阶文字层级。
 */
object UiKit { // 语法：object=单例对象，UI 样式统一工厂，全 App 共用

    /** 按钮样式 */
    enum class Style { NORMAL, PRIMARY, DANGER } // 语法：enum class=枚举类，定义按钮三种样式：灰=普通/绿=主操作/红=危险

    // ===== 基础 =====

    /** dp → px */
    fun dp(context: Context, value: Number): Int = // 语法：fun=函数声明，把 dp 值按屏幕密度换算为像素
        (value.toFloat() * context.resources.displayMetrics.density).toInt() // 语法：toFloat() 转浮点，乘 density 后 toInt() 取整得到 px

    /** 圆角矩形背景 */
    fun rounded( // 语法：fun=函数声明，生成圆角矩形背景（GradientDrawable）
        context: Context, // 语法：参数声明，Context 用于读取屏幕密度
        color: Int, // 背景填充色（ARGB 整型颜色）
        radiusDp: Number, // 圆角半径（dp 单位）
        strokeColor: Int? = null, // 描边颜色（语法：Int?=可空类型，=null 为默认参数，不传则无描边）
        strokeWidthDp: Number = 0 // 描边宽度（语法：默认参数=0，不传时使用）
    ): GradientDrawable { // 语法：返回类型 GradientDrawable=渐变圆角背景
        return GradientDrawable().apply { // 语法：return 返回 + apply 作用域函数，apply 块内可省略对象名
            shape = GradientDrawable.RECTANGLE // 设置形状为矩形
            setColor(color) // 设置填充色
            this.cornerRadius = dp(context, radiusDp).toFloat() // 语法：cornerRadius=圆角半径，dp 转 px 后转 Float
            if (strokeColor != null) { // 语法：if 条件判断，仅当描边颜色非空时执行
                setStroke(dp(context, strokeWidthDp), strokeColor) // 设置描边宽度与颜色
            } // 结束 if 描边判断
        } // 结束 apply 作用域块
    } // 结束 rounded 函数

    /** 带按下态反馈的圆角按钮背景 */
    fun buttonBackground(context: Context, style: Style): StateListDrawable { // 语法：fun=函数声明，生成按下/正常两态按钮背景
        val (normal, pressed) = when (style) { // 语法：val=只读变量 + 解构赋值 + when 表达式，取样式对应的颜色对
            Style.NORMAL -> 0xCC444444.toInt() to 0xCC6E6E6E.toInt() // 普通样式：灰底（正常色 to 按下色）
            Style.PRIMARY -> 0xFF2E7D32.toInt() to 0xFF388E3C.toInt() // 主操作样式：绿底（正常色 to 按下亮绿）
            Style.DANGER -> 0xCCB71C1C.toInt() to 0xCCD32F2F.toInt() // 危险样式：红底（正常色 to 按下亮红）
        } // 结束 when 表达式
        return StateListDrawable().apply { // 语法：return + apply 作用域函数，构建状态列表背景
            addState( // 为状态列表添加一种状态映射
                intArrayOf(android.R.attr.state_pressed), // 语法：intArrayOf=构造整型数组，指定“按下”状态
                rounded(context, pressed, 10f) // 按下状态使用按下色、10dp 圆角
            ) // 结束 addState 调用
            addState( // 再添加默认状态映射
                intArrayOf(), // 空数组表示默认（未按下）状态
                rounded(context, normal, 10f) // 默认状态使用正常色、10dp 圆角
            ) // 结束 addState 调用
        } // 结束 apply 作用域块
    } // 结束 buttonBackground 函数

    // ===== 文本 =====

    /** 页面大标题 */
    fun bigTitle(context: Context, text: String): TextView { // 语法：fun=函数声明，返回页面大标题 TextView 控件
        return TextView(context).apply { // 语法：return + apply 作用域函数，创建 TextView 并配置
            this.text = text // 设置标题文本
            textSize = 22f // 设置字号为 22sp
            setTextColor(0xFFFFFFFF.toInt()) // 语法：setTextColor=设置文字颜色，此处为白色
            typeface = Typeface.DEFAULT_BOLD // 语法：typeface=字体，使用系统默认粗体
        } // 结束 apply 作用域块
    } // 结束 bigTitle 函数

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
    fun titleBar(context: Context, title: String, onBack: () -> Unit): LinearLayout { // 语法：fun=函数声明；onBack: () -> Unit=函数类型参数（Lambda 回调）
        return LinearLayout(context).apply { // 语法：return + apply 作用域函数，创建标题栏线性布局
            orientation = LinearLayout.HORIZONTAL // 设置布局方向为水平
            gravity = android.view.Gravity.CENTER_VERTICAL // 语法：gravity=子控件对齐方式，垂直居中
            // 返回按钮（圆角灰底，文字型）
            addView(TextView(context).apply { // 语法：addView=添加子控件；内嵌 apply 配置返回按钮
                text = "‹ 返回" // 设置按钮文字
                textSize = 15f // 设置字号 15sp
                setTextColor(0xFFFFFFFF.toInt()) // 设置文字为白色
                setPadding(dp(context, 10), dp(context, 6), dp(context, 12), dp(context, 6)) // 语法：setPadding=设置内边距（左/上/右/下）
                background = rounded(context, 0x33444444.toInt(), 8) // 语法：background=设置背景，半透明灰圆角底
                setOnClickListener { onBack() } // 语法：setOnClickListener=设置点击监听，点击时调用 onBack 回调
            }) // 结束返回按钮 apply 块并 addView 添加
            // 标题（占据剩余宽度，左对齐）
            addView(bigTitle(context, title), LinearLayout.LayoutParams( // 语法：addView 带布局参数；LinearLayout.LayoutParams=布局参数对象
                LinearLayout.LayoutParams.MATCH_PARENT, // 语法：MATCH_PARENT=宽度占满父容器
                LinearLayout.LayoutParams.WRAP_CONTENT, // 语法：WRAP_CONTENT=高度随内容自适应
                1f // 语法：权重 weight=1，占据剩余宽度
            ).apply { marginStart = dp(context, 12) }) // 语法：marginStart=设置左侧外边距 12dp
        } // 结束 apply 作用域块
    } // 结束 titleBar 函数

    /** 区块标题（卡片内） */
    fun sectionTitle(context: Context, text: String): TextView { // 语法：fun=函数声明，返回区块标题 TextView 控件
        return TextView(context).apply { // 语法：return + apply 作用域函数，创建并配置 TextView
            this.text = text // 设置标题文本
            textSize = 15f // 设置字号 15sp
            setTextColor(0xFFCCCCCC.toInt()) // 语法：setTextColor=设置文字颜色，浅灰色
            typeface = Typeface.DEFAULT_BOLD // 使用系统默认粗体
        } // 结束 apply 作用域块
    } // 结束 sectionTitle 函数

    /** 说明/状态文字 */
    // 语法：fun=函数声明，color/sizeSp 为带默认值的参数（颜色默认浅灰、字号默认 12sp），返回 TextView
    fun caption(context: Context, text: String, color: Int = 0xFFAAAAAA.toInt(), sizeSp: Float = 12f): TextView {
        return TextView(context).apply { // 语法：return + apply 作用域函数，创建说明文字 TextView
            this.text = text // 设置显示文本
            this.textSize = sizeSp // 设置字号为传入值
            setTextColor(color) // 语法：setTextColor=设置文字颜色，使用传入的颜色
            setLineSpacing(0f, 1.3f) // 语法：setLineSpacing=设置行距（额外间距 0，倍数 1.3）
        } // 结束 apply 作用域块
    } // 结束 caption 函数

    /** 字段标签（输入框上方的小标题） */
    fun label(context: Context, text: String): TextView { // 语法：fun=函数声明，返回字段标签 TextView
        return TextView(context).apply { // 语法：return + apply 作用域函数，创建并配置标签
            this.text = text // 设置标签文本
            textSize = 12f // 设置字号 12sp
            setTextColor(0xFFCCCCCC.toInt()) // 语法：setTextColor=设置文字颜色，浅灰色
        } // 结束 apply 作用域块
    } // 结束 label 函数

    // ===== 容器 =====

    /** 圆角深色卡片容器 */
    fun card(context: Context, paddingDp: Number = 14): LinearLayout { // 语法：fun=函数声明，返回深色圆角卡片容器（默认内边距 14dp）
        return LinearLayout(context).apply { // 语法：return + apply 作用域函数，创建卡片 LinearLayout
            orientation = LinearLayout.VERTICAL // 设置布局方向为垂直
            background = rounded(context, 0xF22A2A2A.toInt(), 14) // 语法：background=设置背景，深色 14dp 圆角
            // 语法：setPadding=设置四边内边距，均为传入的 paddingDp 值（dp 转像素）
            setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
        } // 结束 apply 作用域块
    } // 结束 card 函数

    /** 卡片间的垂直间距 */
    fun spacer(context: Context, heightDp: Number = 12): View { // 语法：fun=函数声明，返回垂直占位间距控件（默认 12dp）
        return View(context).apply { // 语法：return + apply 作用域函数，创建空白 View
            layoutParams = LinearLayout.LayoutParams( // 语法：layoutParams=设置布局参数
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, heightDp) // 宽占满父容器，高为间距值（dp 转 px）
            ) // 结束 LayoutParams 构造
        } // 结束 apply 作用域块
    } // 结束 spacer 函数

    // ===== 控件 =====

    /** 圆角功能按钮（绿=主操作 / 红=危险 / 灰=普通） */
    fun button( // 语法：fun=函数声明，生成圆角功能按钮
        context: Context, // 语法：参数声明，Context 上下文对象
        label: String, // 按钮显示文字
        onClick: () -> Unit, // 语法：函数类型参数，点击回调
        style: Style = Style.NORMAL, // 语法：默认参数，按钮样式默认普通
        heightDp: Number = 44 // 语法：默认参数，按钮高度默认 44dp
    ): Button { // 语法：返回类型为 Button 按钮控件
        return Button(context).apply { // 语法：return + apply 作用域函数，创建并配置按钮
            text = label // 设置按钮文字
            textSize = 14f // 设置字号 14sp
            setTextColor(0xFFFFFFFF.toInt()) // 设置文字为白色
            isAllCaps = false // 语法：isAllCaps=是否全大写，关闭以保留原文
            // 不获取焦点：避免点击后 ScrollView 自动滚动到按钮（触屏下点击回调不受影响）
            isFocusable = false // 语法：isFocusable=是否可获焦点，false 避免滚动吸附
            isFocusableInTouchMode = false // 语法：isFocusableInTouchMode=触屏模式可获焦点，一并关闭
            background = buttonBackground(context, style) // 语法：background=设置带按下态反馈的背景
            setOnClickListener { onClick() } // 语法：setOnClickListener=点击监听，点击时触发 onClick 回调
            layoutParams = LinearLayout.LayoutParams( // 语法：layoutParams=设置布局参数
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, heightDp) // 宽占满父容器，高为传入值（dp 转 px）
            ) // 结束 LayoutParams 构造
        } // 结束 apply 作用域块
    } // 结束 button 函数

    /** 输入框（系统默认样式，文字/提示色适配深色背景） */
    fun input(context: Context, hint: String, value: String = ""): EditText { // 语法：fun=函数声明，返回文本输入框 EditText（value 为带默认值参数）
        return EditText(context).apply { // 语法：return + apply 作用域函数，创建并配置输入框
            this.hint = hint // 设置提示文字
            setText(value) // 设置当前文本（默认空串）
            setTextColor(0xFFFFFFFF.toInt()) // 设置输入文字为白色
            setHintTextColor(0x88AAAAAA.toInt()) // 语法：setHintTextColor=设置提示文字颜色，半透明浅灰
            layoutParams = LinearLayout.LayoutParams( // 语法：layoutParams=设置布局参数
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT // 宽占满父容器，高随内容自适应
            ) // 结束 LayoutParams 构造
        } // 结束 apply 作用域块
    } // 结束 input 函数

    /** 开关行（文本 + Switch） */
    // 语法：fun=函数声明，返回 Switch 开关控件；onChecked: (Boolean) -> Unit=开关状态变化回调
    fun switchRow(context: Context, labelText: String, checked: Boolean, onChecked: (Boolean) -> Unit): android.widget.Switch {
        return android.widget.Switch(context).apply { // 语法：return + apply 作用域函数，创建并配置开关控件
            text = labelText // 设置开关旁的文字
            isChecked = checked // 语法：isChecked=设置初始选中状态
            setTextColor(0xFFDDDDDD.toInt()) // 设置文字为浅灰白
            setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) } // 语法：setOnCheckedChangeListener=开关变化监听，_ 忽略旧值，箭头传新状态给回调
            layoutParams = LinearLayout.LayoutParams( // 语法：layoutParams=设置布局参数
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT // 宽占满父容器，高随内容自适应
            ) // 结束 LayoutParams 构造
        } // 结束 apply 作用域块
    } // 结束 switchRow 函数

    /** 根布局深色背景 */
    fun applyDarkBackground(view: View, context: Context) { // 语法：fun=函数声明，为根视图设置深色背景
        view.background = rounded(context, 0xF21C1C1C.toInt(), 0) // 语法：background=设置背景，近黑深色、无圆角
    } // 结束 applyDarkBackground 函数
} // 结束 UiKit 单例对象
