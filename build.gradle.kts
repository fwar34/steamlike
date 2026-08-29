plugins {  // 语法：plugins{} 块，用于统一声明本项目需要使用的 Gradle 插件
    id("com.android.application") version "9.3.1" apply false  // 语法：id(...) version ... apply false 声明插件及其版本；apply false 表示仅把插件加入类路径、不在根项目实际应用，供 app 子模块引用
}  // 结束 plugins 块
