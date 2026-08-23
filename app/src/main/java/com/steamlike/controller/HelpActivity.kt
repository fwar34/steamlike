package com.steamlike.controller

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.steamlike.controller.config.ControllerConfig
import com.steamlike.controller.core.ControllerButton
import com.steamlike.controller.core.ControllerProfile
import com.steamlike.controller.core.MappedAction
import com.steamlike.controller.ui.UiKit
import java.io.File

/**
 * 使用说明页面
 *
 * 独立的帮助文档页面，与 MainActivity 使用同一套深色卡片 UI 风格（[UiKit]）。
 * 内容根据最新实现维护：
 * - 架构：Android 焦点窗口 + IME 键盘 → TCP(27015) → Windows SendInput 注入
 * - 操作层切换由公共层中的 SwitchLayer 映射驱动（triggerButton 仅 UI 字段）
 * - 悬浮窗三态图标（🎮未连接 / ▶映射中 / ⏸暂停）
 * - 层按钮短按跳转设置页、右键锁存边框高亮、按键按下高亮反馈等新特性
 *
 * 操作层切换说明根据当前 profile 动态生成（[buildLayerSwitchLines]），
 * 避免文档与用户实际配置脱节。
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    /**
     * 构建使用说明页面 UI（深色卡片风格，可滚动）
     */
    private fun buildUi() {
        // 根布局：可滚动容器
        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        UiKit.applyDarkBackground(scroll, this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@HelpActivity, 16), UiKit.dp(this@HelpActivity, 16),
                UiKit.dp(this@HelpActivity, 16), UiKit.dp(this@HelpActivity, 24))
        }
        scroll.addView(container)

        // 页面标题
        container.addView(UiKit.bigTitle(this, "📖 使用说明"))
        container.addView(UiKit.spacer(this, 4))
        container.addView(UiKit.caption(this, "SteamLike Controller 使用指南（随版本持续更新）", 0xFF888888.toInt(), 11f))
        container.addView(UiKit.spacer(this, 14))

        // ===== 一、架构概览 =====
        container.addView(makeCard("架构概览",
            "Android 端通过 1x1 透明焦点窗口捕获手柄输入，经「公共层 + 10 个操作层」映射系统\n"
                + "转换后，通过 TCP(27015) 发送给 Windows 客户端；\n"
                + "Windows 端用 SendInput() 把事件注入 Winlator 中的 WoW。\n\n"
                + "软键盘输入（IME）也走同一链路：焦点窗口持有软键盘 → 捕获文本 → TCP 转发 →\n"
                + "Windows 端按键注入，无需额外驱动。"))

        // ===== 二、快速开始 =====
        container.addView(makeCard("快速开始（Android 端）",
            "1. 授予悬浮窗权限\n"
                + "2. 连接手柄（蓝牙/OTG），确认悬浮窗显示「🎮 未连接」变为层名\n"
                + "3. 点击「启动手柄映射」，悬浮窗显示「▶」（映射中）\n"
                + "4. 切到 Winlator 运行 WoW"))

        container.addView(makeCard("快速开始（Windows 端）",
            "1. 点「导出 Windows 客户端」，从 Download/AControler\n"
                + "   取出 inputbridge_client.exe 与 control.bat\n"
                + "2. 复制到 Winlator 的 C 盘\n"
                + "3. 运行 control.bat start（或直接运行 exe）\n"
                + "4. 保持窗口打开，切回游戏"))

        // ===== 三、悬浮窗状态图标 =====
        container.addView(makeCard("悬浮窗状态图标",
            "收起胶囊显示当前状态：\n"
                + "· 🎮 未连接 —— 手柄未连接，映射未生效\n"
                + "· 层名 ▶ —— 映射中（手柄捕获生效）\n"
                + "· 层名 ⏸ —— 捕获暂停（映射不工作）\n\n"
                + "右键锁存（长按右键）时悬浮窗边框变红色，松开恢复，方便战斗中确认右键状态。"))

        // ===== 四、操作层系统 =====
        container.addView(makeCard("操作层系统（公共层 + 10 操作层）",
            "映射分为「公共层 + 10 个操作层」（仿 Steam Input）。\n"
                + "· 公共层：全局生效的按键，也可配置「切层」映射（SwitchLayer）\n"
                + "· 操作层：战斗/飞行/界面等场景专用配置\n\n"
                + "【切层方式】\n"
                + "· 在公共层给某手柄键配置「切层」动作，按下即激活对应操作层\n"
                + "· 再次按下/配置切换回公共层的映射，可回到公共层\n"
                + "· 层编辑页的「触发按键」只是 UI 标记，实际切换由 SwitchLayer 映射驱动"))

        // ===== 五、操作层设置 =====
        container.addView(makeCard("操作层设置",
            "· 应用内：配置管理 → 操作层设置\n"
                + "· 悬浮窗：展开面板短按操作层按钮 → 直接跳转到该层的设置页\n\n"
                + "设置页顶部显示当前层名称与已映射按键数量；\n"
                + "列表逐行显示每个手柄按键的映射，点击可编辑；\n"
                + "按住手柄按键，对应行会高亮，方便对照确认。"))

        // ===== 六、操作层切换说明（动态） =====
        container.addView(makeCard("当前配置的切层说明", buildLayerSwitchLines()))

        // ===== 七、键盘输入 =====
        container.addView(makeCard("键盘输入（切换键盘）",
            "· 手柄键「切换键盘」可弹出/收起软键盘\n"
                + "· 弹出软键盘时保持捕获（不暂停），输入内容经 IME 转发到 Windows 注入游戏\n"
                + "· 点击键盘收起按钮、返回键或再次按切换键，键盘隐藏后自动恢复捕获\n"
                + "· 可打印字符（字母/数字/符号）走文本通道，方向键/回车/退格等走按键通道"))

        // ===== 八、暂停捕获 =====
        container.addView(makeCard("暂停捕获",
            "· 暂停会移除手柄焦点窗口，侧滑返回手势恢复正常\n"
                + "· 代价：暂停后手柄事件无法到达手机，「切换捕获」手柄键无法恢复，\n"
                + "  需用悬浮窗「恢复捕获」按钮或 App 内「手柄捕获」开关恢复"))

        // ===== 九、智能暂停 =====
        container.addView(makeCard("智能暂停",
            "· 开启后自动检测前台应用：白名单（如 Winlator）在前台时保持手柄捕获，\n"
                + "  切到其他应用自动暂停捕获，右滑返回恢复正常\n"
                + "· 需授权「使用情况访问」"))

        // ===== 十、悬浮窗操作 =====
        container.addView(makeCard("悬浮窗操作",
            "· 收起胶囊显示当前层与状态图标，点击展开面板，可拖动\n"
                + "· 「游戏」拉起 Winlator；「暂停/恢复」切换捕获（与 App 内开关同步）\n"
                + "· 层按钮：短按跳转该层设置页；长按激活层、松开回公共层\n"
                + "· 「清除层」清空激活层；「映射」查看按键映射页（按键按下高亮）\n"
                + "· 「关闭」停止服务"))

        // ===== 十一、配置管理 =====
        container.addView(makeCard("配置管理",
            "· 导出 / 导入：JSON 完整备份（含运行时设置）\n"
                + "· 重置配置：恢复默认 WoW 预设\n"
                + "· 游戏 EXE 路径：导出后 control.bat 先启动客户端，成功后再自动启动游戏"))

        setContentView(scroll)
    }

    /**
     * 创建一个说明区块卡片（标题 + 正文）
     *
     * @param title 区块标题
     * @param body 正文（支持 \n 换行）
     * @return 卡片容器视图
     */
    private fun makeCard(title: String, body: String): LinearLayout {
        val card = UiKit.card(this)
        card.addView(UiKit.sectionTitle(this, title))
        card.addView(UiKit.spacer(this, 4))
        card.addView(UiKit.caption(this, body, 0xFFCCCCCC.toInt(), 12f))
        return card
    }

    /**
     * 获取当前生效的 ControllerProfile
     *
     * 数据源优先级:
     * 1. 服务已启动: 读取运行时 [LayerEditActivity.steamInputRef] 的 profile
     * 2. 服务未启动但配置文件存在: 从内部配置文件加载
     * 3. 都没有: 使用代码内置默认 profile
     */
    private fun getCurrentProfile(): ControllerProfile {
        LayerEditActivity.steamInputRef?.profile?.let { return it }
        val configFile = File(filesDir, "steamlike_config.json")
        if (configFile.exists()) {
            return try {
                ControllerConfig.fromJson(configFile.readText())
            } catch (e: Exception) {
                ControllerProfile.createDefault()
            }
        }
        return ControllerProfile.createDefault()
    }

    /**
     * 根据当前 profile 动态构建操作层切换说明
     *
     * 显示每个操作层当前的「切层键」配置（[MappedAction.SwitchLayer] 映射），
     * 便于用户直接查看当前生效的切换按键。若未配置切层映射则提示未配置。
     *
     * @return 多行字符串，每行一个操作层
     */
    private fun buildLayerSwitchLines(): String {
        val profile = getCurrentProfile()
        val lines = profile.layers.map { layer ->
            // 公共层中切到该操作层的映射键
            val switchKeys = profile.commonLayer.buttonMappings
                .filterValues { mapping ->
                    (mapping.action as? MappedAction.SwitchLayer)?.layerName == layer.name
                }
                .map { buttonDisplayName(it.key) }
            // 该层中切回其他层（通常为公共层）的映射键
            val backKeys = layer.buttonMappings
                .filterValues { mapping -> mapping.action is MappedAction.SwitchLayer }
                .map { buttonDisplayName(it.key) }
            val switchText = if (switchKeys.isEmpty()) "未配置" else switchKeys.joinToString("/")
            val backText = if (backKeys.isEmpty()) "未配置" else backKeys.joinToString("/")
            "· ${layer.name}  切入:$switchText  切回:$backText"
        }
        return lines.joinToString("\n")
    }

    /**
     * 将 ControllerButton 转换为可读名称
     *
     * 与 LayerEditActivity.buttonDisplayName 保持一致。
     */
    private fun buttonDisplayName(button: ControllerButton): String = when (button) {
        ControllerButton.A -> "A"
        ControllerButton.B -> "B"
        ControllerButton.X -> "X"
        ControllerButton.Y -> "Y"
        ControllerButton.LEFT_SHOULDER -> "LB"
        ControllerButton.RIGHT_SHOULDER -> "RB"
        ControllerButton.LEFT_TRIGGER_CLICK -> "L2"
        ControllerButton.RIGHT_TRIGGER_CLICK -> "R2"
        ControllerButton.LEFT_STICK_CLICK -> "L3"
        ControllerButton.RIGHT_STICK_CLICK -> "R3"
        ControllerButton.MENU -> "Menu"
        ControllerButton.OPTIONS -> "Options"
        ControllerButton.GUIDE -> "Guide"
        ControllerButton.DPAD_UP -> "D-Pad ↑"
        ControllerButton.DPAD_DOWN -> "D-Pad ↓"
        ControllerButton.DPAD_LEFT -> "D-Pad ←"
        ControllerButton.DPAD_RIGHT -> "D-Pad →"
        ControllerButton.TOUCHPAD_CLICK -> "Touchpad"
    }
}
