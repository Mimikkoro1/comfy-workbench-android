-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlin.**
-dontwarn androidx.compose.**

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

-dontwarn coil.**
-keep class coil.** { *; }

-keep class org.json.** { *; }
-keep class com.mie.kreaworkbench.data.** { *; }
-keep class com.mie.kreaworkbench.service.GenerationService { *; }
-keep class com.mie.kreaworkbench.service.SshTunnelService { *; }

# JSch (SSH tunnel): it loads the crypto/key implementations in com.jcraft.jsch.jce.* by *name*
# through reflection; renamed or stripped, they blow up at runtime only - and right inside this feature. Keep the whole package.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
