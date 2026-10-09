# 保留 core 领域模型与 Agent 数据类（序列化/反射访问）
-keep class com.inkflow.core.** { *; }
-keepclassmembers class com.inkflow.core.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.inkflow.**$$serializer { *; }
-keepclassmembers class com.inkflow.** { *** Companion; }
-keepclasseswithmembers class com.inkflow.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit GenAI（AICore）为可选依赖，缺失时不应导致构建/运行失败
-dontwarn com.google.mlkit.genai.**
-keep class com.google.mlkit.genai.** { *; }

# LiteRT-LM 走 JNI，需保留入口类
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
