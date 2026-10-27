# ============================================================================
# core-nfcc 是纯算法模块，混淆没有任何安全收益，反而让崩溃栈不可读。
# release 不开 minify 时本文件不被使用；等 M2 打开 R8 时这些规则才会生效。
# ============================================================================

# ---- kotlinx.serialization ------------------------------------------------
# 本站没有用 @Serializable 的泛型/多态，只需要保住 Companion 与 serializer()
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.nfcemu.**$$serializer { *; }
-keep,includedescriptorclasses class io.github.nfcemu.**$Companion { *; }
-dontwarn kotlinx.serialization.**

# ---- core-nfcc 全部保留 ---------------------------------------------------
# 这个模块是"真值来源"，被 app 反射调用/单元测试覆盖，混淆收益为零
-keep class io.github.nfcemu.core.** { *; }

# ---- HostApduService 由系统按类名实例化 ------------------------------------
# AndroidManifest 里注册的 Service 类名在 R8 后必须保持不变，否则系统找不到
-keep class io.github.nfcemu.hce.** { *; }

# ---- 数据模型（未来可能被 JSON 反序列化） ---------------------------------
-keep class io.github.nfcemu.domain.** { *; }

# ---- 杂项 -----------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
