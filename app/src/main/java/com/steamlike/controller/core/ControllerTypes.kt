package com.steamlike.controller.core

/**
 * Steam风格输入动作类型
 *
 * 用于区分三种不同的输入动作类型，在 [InputAction] 的子类中使用。
 */
enum class InputActionType {
    /** 按钮类型: 二进制输入（按下/释放） */
    BUTTON,

    /** 模拟扳机类型: 连续值输入（0.0~1.0） */
    ANALOG_TRIGGER,

    /** 摇杆/触控板/陀螺仪类型: 2D向量输入 */
    STICK_PAD_GYRO
}

/**
 * 手柄控制器类型（对应Steam支持的手柄类型）
 *
 * 不同类型的手柄可能有不同的按键布局（如 Xbox 的 A/B/X/Y 与 PS 的 ×/○/□/△ 位置不同），
 * [ControllerInputMapper] 会根据控制器类型自动修正按键映射。
 *
 * 通过 USB Vendor ID 和 Product ID 识别手柄类型。
 *
 * @param vendorId USB厂商ID
 * @param productId USB产品ID（null=不区分具体型号）
 */
enum class ControllerType(val vendorId: Int, val productId: Int? = null) {
    /** Xbox 360 手柄 */
    XBOX_360(0x045E, 0x028E),

    /** Xbox One 手柄 */
    XBOX_ONE(0x045E, 0x02DD),

    /** Xbox Elite 精英手柄 */
    XBOX_ELITE(0x045E, 0x0B00),

    /** PlayStation 3 手柄 (DualShock 3) */
    PS3(0x054C, 0x0268),

    /** PlayStation 4 手柄 (DualShock 4) */
    PS4(0x054C, 0x05C4),

    /** PlayStation 5 手柄 (DualSense) */
    PS5_DUALSENSE(0x054C, 0x0CE6),

    /** Nintendo Switch Pro 手柄 */
    SWITCH_PRO(0x057E, 0x2009),

    /** Steam Controller 手柄 */
    STEAM_CONTROLLER(0x28DE, 0x1102),

    /** Steam Deck 掌机 */
    STEAM_DECK(0x28DE, 0x1205),

    /** 通用手柄（无法识别具体类型时使用） */
    GENERIC(-1);

    companion object {
        /**
         * 根据USB Vendor ID和Product ID识别手柄类型
         *
         * @param vendorId USB厂商ID
         * @param productId USB产品ID
         * @return 对应的 [ControllerType]，未识别则返回 [GENERIC]
         */
        fun fromVendorProduct(vendorId: Int, productId: Int): ControllerType {
            return values().firstOrNull { it.vendorId == vendorId && it.productId == productId }
                ?: GENERIC
        }
    }
}

/**
 * 标准手柄按键映射（跨平台统一）
 *
 * 无论使用什么类型的手柄（Xbox/PS/Switch），按键都统一映射到此枚举。
 * [ControllerInputMapper] 负责将不同手柄的物理按键映射到此统一编码。
 *
 * ## 按键说明
 * - A/B/X/Y: 面部按钮（Xbox布局: A=下, B=右, X=左, Y=上）
 * - LEFT_SHOULDER/RIGHT_SHOULDER: 肩键（LB/RB）
 * - LEFT_TRIGGER_CLICK/RIGHT_TRIGGER_CLICK: 扳机按键（L2/R2 按到底的点击）
 * - LEFT_STICK_CLICK/RIGHT_STICK_CLICK: 摇杆按下（L3/R3）
 * - MENU: 菜单键（Xbox=Menu, PS=Options, 通常在右侧）
 * - OPTIONS: 选项键（Xbox=View, PS=Create, 通常在左侧）
 * - GUIDE: 中央Home键（Xbox=大圆按钮, PS=PS按钮）
 * - DPAD_UP/DOWN/LEFT/RIGHT: 十字键
 * - TOUCHPAD_CLICK: 触控板点击（PS4/PS5专属）
 */
enum class ControllerButton {
    A, B, X, Y,                                           // 面部按钮
    LEFT_SHOULDER, RIGHT_SHOULDER,                        // 肩键 LB/RB
    LEFT_TRIGGER_CLICK, RIGHT_TRIGGER_CLICK,              // 扳机点击 L2/R2
    LEFT_STICK_CLICK, RIGHT_STICK_CLICK,                   // 摇杆按下 L3/R3
    MENU, OPTIONS, GUIDE,                                 // 菜单/选项/Home键
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,            // 十字键
    TOUCHPAD_CLICK                                        // 触控板点击
}

/**
 * 手柄摇杆类型
 *
 * 摇杆是2D模拟量输入，X/Y范围均为 -1.0~1.0。
 */
enum class ControllerStick {
    /** 左摇杆 */
    LEFT_STICK,

    /** 右摇杆 */
    RIGHT_STICK,

    /** 十字键作为摇杆使用（某些场景需要将D-Pad作为方向输入） */
    DPAD_AS_STICK
}

/**
 * 手柄扳机类型
 *
 * 扳机是1D模拟量输入，范围为 0.0（完全释放）~1.0（完全按下）。
 */
enum class ControllerTrigger {
    /** 左扳机 (LT/L2/ZL) */
    LEFT_TRIGGER,

    /** 右扳机 (RT/R2/ZR) */
    RIGHT_TRIGGER
}

/**
 * 2D向量
 *
 * 用于表示摇杆的位置，X/Y范围均为 -1.0~1.0。
 *
 * ## 坐标系
 * - X轴: 右为正，左为负
 * - Y轴: 下为正，上为负（与Android屏幕坐标一致）
 *
 * @param x X轴分量
 * @param y Y轴分量
 */
data class Vector2(
    val x: Float = 0f,
    val y: Float = 0f
) {
    /** 向量长度（幅度），范围 0.0~1.0 */
    val magnitude: Float get() = kotlin.math.sqrt(x * x + y * y)

    /**
     * 返回归一化向量（长度为1.0）
     *
     * @return 归一化后的向量，零向量返回零向量
     */
    fun normalized(): Vector2 {
        val mag = magnitude
        return if (mag > 0f) Vector2(x / mag, y / mag) else Vector2(0f, 0f)
    }

    /**
     * 应用死区: 小于死区的输入归零
     *
     * 死区用于消除摇杆中心附近的小幅漂移。
     * 死区外的输入会被重新缩放到 0~1 范围。
     *
     * @param deadzone 死区值（0.0~1.0）
     * @return 处理后的向量，死区内返回零向量
     */
    fun withDeadzone(deadzone: Float): Vector2 {
        val mag = magnitude
        if (mag < deadzone) return Vector2(0f, 0f)
        // 死区外的输入重新缩放: (mag - deadzone) / (1 - deadzone)
        val scale = (mag - deadzone) / (1f - deadzone)
        val norm = normalized()
        return Vector2(norm.x * scale, norm.y * scale)
    }

    companion object {
        /** 零向量（摇杆居中） */
        val ZERO = Vector2(0f, 0f)
    }
}

/**
 * 手柄输入数据快照
 *
 * 存储某个时刻手柄的完整输入状态，用于状态查询和差量计算。
 *
 * @param deviceId 设备ID
 * @param timestamp 时间戳（来自输入事件）
 * @param buttons 按钮状态表（Button → 是否按下）
 * @param sticks 摇杆位置表（Stick → Vector2）
 * @param triggers 扳机值表（Trigger → Float）
 */
data class ControllerState(
    val deviceId: Int,
    val timestamp: Long,
    val buttons: Map<ControllerButton, Boolean> = emptyMap(),
    val sticks: Map<ControllerStick, Vector2> = emptyMap(),
    val triggers: Map<ControllerTrigger, Float> = emptyMap()
) {
    /** 检查指定按钮是否按下 */
    fun isButtonPressed(button: ControllerButton): Boolean = buttons[button] == true

    /** 获取指定摇杆的位置 */
    fun getStick(stick: ControllerStick): Vector2 = sticks[stick] ?: Vector2.ZERO

    /** 获取指定扳机的值 */
    fun getTrigger(trigger: ControllerTrigger): Float = triggers[trigger] ?: 0f
}
