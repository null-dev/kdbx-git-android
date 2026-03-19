# Keep Room entity and DAO classes
-keep class ax.nd.kdbxgit.android.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Optional logging / annotation references seen only by R8 in release builds.
-dontwarn ch.qos.logback.classic.Level
-dontwarn ch.qos.logback.classic.Logger
-dontwarn ch.qos.logback.classic.LoggerContext
-dontwarn ch.qos.logback.classic.spi.ILoggingEvent
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn org.slf4j.event.KeyValuePair
-dontwarn org.slf4j.spi.CallerBoundaryAware
-dontwarn org.slf4j.spi.LoggingEventBuilder
