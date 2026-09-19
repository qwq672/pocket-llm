# Keep native bridge
-keep class com.pocketllm.infra.jni.** { *; }

# Keep data models used by Room / Moshi
-keep class com.pocketllm.data.model.** { *; }
-keep class com.pocketllm.data.db.** { *; }

# Compose
-dontwarn androidx.compose.**

# OkHttp / Retrofit / Moshi
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn okio.**
-dontwarn com.squareup.moshi.**
-keepclassmembers class * { @com.squareup.moshi.* <methods>; }
