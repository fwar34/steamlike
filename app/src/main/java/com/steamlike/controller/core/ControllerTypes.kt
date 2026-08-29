package com.steamlike.controller.core // 包声明（语法：package 声明当前文件所属包）

/**
 * Steam风格输入动作类型
 *
 * 用于区分三种不同的输入动作类型，在 [InputAction] 的子类中使用。
 */
enum class InputActionType { // 输入动作类型枚举（语法：enum class 枚举类）
    /** 按钮类型: 二进制输入（按下/释放） */
    BUTTON, // 按钮动作（语法：枚举常量）

    /** 模拟扳机类型: 连续值输入（0.0~1.0） */
    ANALOG_TRIGGER, // 模拟扳机动作

    /** 摇杆/触控板/陀螺仪类型: 2D向量输入 */
    STICK_PAD_GYRO // 摇杆/触控板/陀螺仪动作
} // 结束 InputActionType 枚举

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
enum class ControllerType(val vendorId: Int, val productId: Int? = null) { // 手柄类型枚举（语法：enum class 带构造参数 + 可空默认参数）
    /** Xbox 360 手柄 */
    XBOX_360(0x045E, 0x028E), // Xbox 360 型号（语法：枚举常量调用构造参数）

    /** Xbox One 手柄 */
    XBOX_ONE(0x045E, 0x02DD), // Xbox One 型号

    /** Xbox Elite 精英手柄 */
    XBOX_ELITE(0x045E, 0x0B00), // Xbox Elite 精英手柄型号

    /** PlayStation 3 手柄 (DualShock 3) */
    PS3(0x054C, 0x0268), // PS3 手柄型号

    /** PlayStation 4 手柄 (DualShock 4) */
    PS4(0x054C, 0x05C4), // PS4 手柄型号

    /** PlayStation 5 手柄 (DualSense) */
    PS5_DUALSENSE(0x054C, 0x0CE6), // PS5 DualSense 手柄型号

    /** Nintendo Switch Pro 手柄 */
    SWITCH_PRO(0x057E, 0x2009), // Switch Pro 手柄型号

    /** Steam Controller 手柄 */
    STEAM_CONTROLLER(0x28DE, 0x1102), // Steam Controller 手柄型号

    /** Steam Deck 掌机 */
    STEAM_DECK(0x28DE, 0x1205), // Steam Deck 掌机型号

    /** 通用手柄（无法识别具体类型时使用） */
    GENERIC(-1); // 通用类型（语法：分号结束枚举常量列表，productId 用默认 null）

    companion object { // 伴生对象（语法：companion object 静态成员容器）
        /**
         * 根据USB Vendor ID和Product ID识别手柄类型
         *
         * @param vendorId USB厂商ID
         * @param productId USB产品ID
         * @return 对应的 [ControllerType]，未识别则返回 [GENERIC]
         */
        fun fromVendorProduct(vendorId: Int, productId: Int): ControllerType { // 按厂商/产品 ID 识别手柄类型（语法：伴生对象内静态函数）
            // 查找 ID 匹配的枚举（语法：values() + firstOrNull lambda + &&；该行超长故注释置上方）
            return values().firstOrNull { it.vendorId == vendorId && it.productId == productId }
                ?: GENERIC // 未匹配则返回通用类型（语法：?: 空值合并跨行）
        } // 结束 fromVendorProduct 函数
    } // 结束 companion object
} // 结束 ControllerType 枚举

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
enum class ControllerButton { // 统一手柄按键枚举（语法：enum class 枚举类）
    A, B, X, Y,                                           // 面部按钮 // 面部四键
    LEFT_SHOULDER, RIGHT_SHOULDER,                        // 肩键 LB/RB // 左右肩键
    LEFT_TRIGGER_CLICK, RIGHT_TRIGGER_CLICK,              // 扳机点击 L2/R2 // 左右扳机点击
    LEFT_STICK_CLICK, RIGHT_STICK_CLICK,                   // 摇杆按下 L3/R3 // 左右摇杆按下
    MENU, OPTIONS, GUIDE,                                 // 菜单/选项/Home键 // 菜单/选项/Home 键
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,            // 十字键 // 十字键四个方向
    TOUCHPAD_CLICK                                        // 触控板点击 // PS 触控板点击
} // 结束 ControllerButton 枚举

/**
 * 鼠标按键类型（类型别名）
 *
 * 指向 [com.steamlike.controller.injection.MouseButton]，统一使用同一个 MouseButton 枚举。
 * 用于 [com.steamlike.controller.core.MappedAction.MouseClick] 动作。
 */
typealias MouseButton = com.steamlike.controller.injection.MouseButton // 鼠标按钮类型别名（语法：typealias 类型别名）

/**
 * 手柄摇杆类型
 *
 * 摇杆是2D模拟量输入，X/Y范围均为 -1.0~1.0。
 */
enum class ControllerStick { // 手柄摇杆类型枚举（语法：enum class 枚举类）
    /** 左摇杆 */
    LEFT_STICK, // 左摇杆

    /** 右摇杆 */
    RIGHT_STICK, // 右摇杆

    /** 十字键作为摇杆使用（某些场景需要将D-Pad作为方向输入） */
    DPAD_AS_STICK // 十字键当摇杆使用
} // 结束 ControllerStick 枚举

/**
 * 手柄扳机类型
 *
 * 扳机是1D模拟量输入，范围为 0.0（完全释放）~1.0（完全按下）。
 */
enum class ControllerTrigger { // 手柄扳机类型枚举（语法：enum class 枚举类）
    /** 左扳机 (LT/L2/ZL) */
    LEFT_TRIGGER, // 左扳机

    /** 右扳机 (RT/R2/ZR) */
    RIGHT_TRIGGER // 右扳机
} // 结束 ControllerTrigger 枚举

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
data class Vector2( // 2D 向量数据类（语法：data class 数据类）
    val x: Float = 0f, // X 轴分量（语法：默认参数 = 0f）
    val y: Float = 0f // Y 轴分量
) { // 数据类主体开始
    /** 向量长度（幅度），范围 0.0~1.0 */
    val magnitude: Float get() = kotlin.math.sqrt(x * x + y * y) // 向量长度（语法：自定义 getter + sqrt 开方）

    /**
     * 返回归一化向量（长度为1.0）
     *
     * @return 归一化后的向量，零向量返回零向量
     */
    fun normalized(): Vector2 { // 归一化向量（语法：fun 成员函数 + 返回 Vector2）
        val mag = magnitude // 计算向量长度
        return if (mag > 0f) Vector2(x / mag, y / mag) else Vector2(0f, 0f) // 非零则缩放到长度 1，零向量返回零向量（语法：if 表达式）
    } // 结束 normalized 函数

    /**
     * 应用死区: 小于死区的输入归零
     *
     * 死区用于消除摇杆中心附近的小幅漂移。
     * 死区外的输入会被重新缩放到 0~1 范围。
     *
     * @param deadzone 死区值（0.0~1.0）
     * @return 处理后的向量，死区内返回零向量
     */
    fun withDeadzone(deadzone: Float): Vector2 { // 应用死区（语法：fun 成员函数 + 返回 Vector2）
        val mag = magnitude // 计算向量长度
        if (mag < deadzone) return Vector2(0f, 0f) // 长度小于死区则返回零向量
        // 死区外的输入重新缩放: (mag - deadzone) / (1 - deadzone)
        val scale = (mag - deadzone) / (1f - deadzone) // 计算缩放系数
        val norm = normalized() // 获取归一化方向
        return Vector2(norm.x * scale, norm.y * scale) // 按缩放系数缩放方向向量
    } // 结束 withDeadzone 函数

    companion object { // 伴生对象（语法：companion object 静态成员容器）
        /** 零向量（摇杆居中） */
        val ZERO = Vector2(0f, 0f) // 零向量常量（语法：伴生对象内 val 常量）
    } // 结束 companion object
} // 结束 Vector2 数据类

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
data class ControllerState( // 手柄输入数据快照（语法：data class 数据类）
    val deviceId: Int, // 设备 ID
    val timestamp: Long, // 时间戳
    val buttons: Map<ControllerButton, Boolean> = emptyMap(), // 按钮状态表（语法：Map 泛型 + 默认参数）
    val sticks: Map<ControllerStick, Vector2> = emptyMap(), // 摇杆位置表
    val triggers: Map<ControllerTrigger, Float> = emptyMap() // 扳机值表
) { // 数据类主体开始
    /** 检查指定按钮是否按下 */
    fun isButtonPressed(button: ControllerButton): Boolean = buttons[button] == true // 判断按钮是否按下（语法：表达式体函数）

    /** 获取指定摇杆的位置 */
    fun getStick(stick: ControllerStick): Vector2 = sticks[stick] ?: Vector2.ZERO // 获取摇杆位置（语法：?: 空值合并返回默认）

    /** 获取指定扳机的值 */
    fun getTrigger(trigger: ControllerTrigger): Float = triggers[trigger] ?: 0f // 获取扳机值（语法：?: 空值合并返回默认）
} // 结束 ControllerState 数据类
