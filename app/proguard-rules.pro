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

# ---------------------------------------------------------------- sherpa-onnx
# JNI 是按「类名 + 方法名 + 字段名」反查的，混淆掉任何一个都会在运行时崩，
# 所以这个包必须整体保留（包括 data class 的字段和 external 方法）。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

