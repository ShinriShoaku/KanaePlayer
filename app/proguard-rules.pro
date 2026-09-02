# optimization & General
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# Keep Kotlin Metadata & Internals
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.jvm.JvmField <fields>;
}
-dontwarn kotlin.jvm.internal.**

# NewPipe Extractor & Rhino (JS Engine)
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.script.**

# App Models (Penting untuk GSON/Refleksi)
-keep class ame.project.kanae.model.** { *; }
-keepclassmembers class ame.project.kanae.model.** { *; }
-keep class ame.project.kanae.AppSettings { *; }
-keep class ame.project.kanae.OverlayConfig { *; }
-keep class ame.project.kanae.CustomThemeConfig { *; }
-keep class ame.project.kanae.GiftSoundConfig { *; }
-keep class ame.project.kanae.StyleThemeConfig { *; }
-keep class ame.project.kanae.KeyMapping { *; }
-keep class ame.project.kanae.SoundMapping { *; }
-keep class ame.project.kanae.overlay.CustomOverlayConfig { *; }
-keep class ame.project.kanae.MainActivity$UpdateInfo { *; }

# GSON rules
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# AIDL / NL Studio SDK
# Pastikan interface dan stub AIDL tidak terhapus/ter-obfuscate agar komunikasi antar app lancar
-keep class ame.project.nlsdk.** { *; }
-keep interface ame.project.nlsdk.** { *; }
-keep class * extends ame.project.nlsdk.IKanaeService$Stub { *; }
-keep class * extends ame.project.nlsdk.IKanaeCallback$Stub { *; }
-dontwarn ame.project.nlsdk.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-dontwarn kotlinx.coroutines.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-dontwarn com.bumptech.glide.Generated*GlideModuleImpl
-dontwarn com.bumptech.glide.load.resource.bitmap.VideoDecoder
# Keep generated Glide modules if they exist
-keep class com.bumptech.glide.Generated*GlideModuleImpl { *; }

# youtube-dl-android (fallback library)
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# Apache Commons Compress (Used by youtube-dl-android)
-keep class org.apache.commons.compress.** { *; }
-keep class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    <init>();
}
-dontwarn org.apache.commons.compress.**

# Firebase Crashlytics
-keepattributes SourceFile,LineNumberTable
-keep public class com.google.firebase.crashlytics.** { *; }
-dontwarn com.google.firebase.crashlytics.**

# Color Picker (top.defaults.colorpicker)
-keep class top.defaults.colorpicker.** { *; }
-dontwarn top.defaults.colorpicker.**

# Keep Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding { *; }
