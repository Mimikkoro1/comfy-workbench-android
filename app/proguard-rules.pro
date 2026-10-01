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

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
