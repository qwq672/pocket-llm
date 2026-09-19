# Keep native bridge
-keep class com.pocketllm.infra.jni.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Keep data models used by Room / Moshi / kotlinx.serialization
-keep class com.pocketllm.data.model.** { *; }
-keep class com.pocketllm.data.db.** { *; }
-keep class com.pocketllm.domain.inference.** { *; }
-keep class com.pocketllm.domain.download.** { *; }
# Moshi Kotlin 反射：保留 data class 构造函数参数
-keepclassmembers class com.pocketllm.** { <init>(...); }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.pocketllm.** { kotlinx.serialization.KSerializer serializer(...); }

# Room generated
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Compose
-dontwarn androidx.compose.**

# OkHttp / Retrofit / Moshi
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn okio.**
-dontwarn com.squareup.moshi.**
-keep class com.squareup.moshi.** { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class * { @com.squareup.moshi.* <methods>; }

# Coroutines / Flow
-dontwarn kotlinx.coroutines.**
