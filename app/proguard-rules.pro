# kotlinx.serialization：编译器插件生成的 serializer 需要保留
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mozinodey.bfilter.**$$serializer { *; }
-keepclassmembers class com.mozinodey.bfilter.** {
    *** Companion;
}
-keepclasseswithmembers class com.mozinodey.bfilter.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3
-dontwarn androidx.media3.**

# Coil
-dontwarn coil.**
