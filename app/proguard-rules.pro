# NewPipe Extractor
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Models
-keep class ame.project.ytplayer.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
