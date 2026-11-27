# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes Signature

# Chaquopy Requirements
-keep class com.chaquo.python.** { *; }
-keep interface com.chaquo.python.** { *; }

# Keep OkHttp/Okio if minified
-dontwarn okio.**
-dontwarn javax.annotation.**

# Keep Compose and Kotlin Reflection
-keep class androidx.compose.material.icons.** { *; }
-keep class androidx.compose.ui.graphics.** { *; }
-keep class androidx.compose.ui.unit.** { *; }
-dontwarn androidx.compose.material.icons.**
-keep class com.conduit.nexus.** { *; }