# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 自定义 View 需要保留构造器（供布局 inflate / 反射使用）
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
