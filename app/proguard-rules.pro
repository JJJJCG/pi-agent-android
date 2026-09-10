# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pi.assistant.**$$serializer { *; }
-keepclassmembers class com.pi.assistant.** { *** Companion; }
-keepclasseswithmembers class com.pi.assistant.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions

# Google Tink 的字节码引用了 errorprone 的编译期注解（compileOnly，不进 APK）。
# 这 4 个类运行时根本用不到，R8 严格模式下需显式豁免，否则 minify 直接失败。
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# ---------------------------------------------------------------- sherpa-onnx
# JNI 是按「类名 + 方法名 + 字段名」反查的，混淆掉任何一个都会在运行时崩，
# 所以这个包必须整体保留（包括 data class 的字段和 external 方法）。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ---------------------------------------------------------------- 发布版清理
# 去掉调试日志（d/v）。i/w/e 保留，方便线上崩溃诊断。
# R8 会据此把这两类调用从字节码里整个删掉，release 包里不再留痕。
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
}

# ViewModel 子类保留：Hilt / 导航按类型反射查找实现，混淆后找不到会崩。
-keep class * extends androidx.lifecycle.ViewModel { *; }

