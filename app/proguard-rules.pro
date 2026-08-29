# 语法：-keep 为 ProGuard 保留规则（keep 规则）
# 保留规则内容：com.steamlike.controller 包及其所有子包下的全部类与类成员不做任何混淆/收缩
# 原因：该类名常被反射、序列化等机制引用（如 Gson/原生反射），混淆会导致运行时找不到类
-keep class com.steamlike.controller.** { *; }
