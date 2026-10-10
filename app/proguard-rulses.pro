# Keep line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep native methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the VPN service and app classes referenced from the manifest
-keep class com.vpn.app.** { *; }
-keep public class * extends android.net.VpnService
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity

# sing-box library (libbox) and Go mobile bindings
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-dontwarn io.nekohasekai.libbox.**
-dontwarn go.**

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
