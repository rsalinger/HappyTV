# HappyTV ProGuard/R8 rules.
# Shared by the `benchmark` build (minify on) and `release` (minify still off;
# these rules apply as soon as it is turned on).

# ------------------------------------------------------------------
# Kotlinx Serialization
# ------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializers for our own @Serializable models
-keep,includedescriptorclasses class com.rysal.happytv.**$$serializer { *; }
-keepclassmembers class com.rysal.happytv.** {
    *** Companion;
}
-keepclasseswithmembers class com.rysal.happytv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------------
# Room
# ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------------
# Media3 / ExoPlayer
# ------------------------------------------------------------------
# Media3 ships consumer rules; these supplement them for reflection-heavy parts.
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.source.** { *; }
-keep class androidx.media3.exoplayer.drm.** { *; }
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.common.util.UnstableApi { *; }

# ------------------------------------------------------------------
# Retrofit / OkHttp
# ------------------------------------------------------------------
# Retrofit builds the XtreamApi proxy reflectively from its annotations and
# generic signatures.
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep interface com.rysal.happytv.data.network.XtreamApi { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ------------------------------------------------------------------
# WorkManager — workers are instantiated by class name via reflection
# ------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ------------------------------------------------------------------
# Debug-only receiver is referenced from main code; keep names readable in traces
# ------------------------------------------------------------------
-keepattributes SourceFile, LineNumberTable
