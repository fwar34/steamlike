package com.steamlike.controller.core

/**
 * 组合键绑定 (Chord Binding)
 *
 * 参考 Steam Input 的子指令(Sub-Command)机制。
 *
 * ## 概念
 * 组合键绑定允许同一个按钮在不同修饰键状态下触发不同动作:
 * - A 单独按下 → Jump
 * - A + LB 同时 → Slot5
 * - A + LB + LT → Potion
 *
 * ## 匹配规则
 * 当按钮按下时，系统检查所有该按钮的组合键绑定:
 * 1. 找出所有 chord 是当前按住按钮集合子集的绑定
 * 2. 选择 chord 最大的绑定（最具体的匹配优先）
 * 3. 如果没有匹配的组合键绑定，使用默认绑定（无chord）
 *
 * @param button 触发按钮（被按下的按钮）
 * @param actionName 要执行的动作名称
 * @param chord 需要同时按住的修饰按钮集合（空集合=默认绑定）
 */
data class ChordBinding(
    val button: ControllerButton,
    val actionName: String,
    val chord: Set<ControllerButton> = emptySet()
) {
    /**
     * 检查此组合键绑定是否匹配当前按住的按钮集合
     *
     * @param heldButtons 当前按住的按钮集合（不含触发按钮本身）
     * @return true=chord是heldButtons的子集
     */
    fun matches(heldButtons: Set<ControllerButton>): Boolean {
        return chord.all { it in heldButtons }
    }

    /** chord 的大小（用于优先级排序，越大越优先） */
    val chordSize: Int get() = chord.size
}
