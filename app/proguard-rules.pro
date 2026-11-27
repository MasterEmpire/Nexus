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
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.Unit { *; }
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.collections.EmptyList { *; }
-keep class kotlin.collections.CollectionsKt** { *; }
-dontwarn androidx.compose.material.icons.**
-keep class com.conduit.nexus.** { *; }