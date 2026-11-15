# JH GetWay R8 / ProGuard Release Rules (JH Soft Corporation)
# Preserves reflection-sensitive JSON/Room/Ktor/OkHttp/Biometric types and stack trace diagnostics.

-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Application entities, gateway contracts, and network adapter payloads
-keep class com.example.data.local.** { *; }
-keep class com.example.gateway.** { *; }
-keep class com.example.network.** { *; }
-keep class com.example.core.security.** { *; }

# Room database & generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    void clearAllTables();
}
-keepclassmembers class * {
    @androidx.room.Entity <fields>;
    @androidx.room.Dao <methods>;
}

# Moshi reflection / KotlinJsonAdapterFactory
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.JsonClass <fields>;
}
-keep class com.squareup.moshi.** { *; }

# Ktor CIO embedded gateway server & coroutines
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keepclassnames class kotlinx.coroutines.android.AndroidDispatcherFactory
-keepclassnames class kotlinx.coroutines.CoroutineExceptionHandler
-dontwarn kotlinx.coroutines.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# SLF4J / optional logging bridges pulled by Ktor
-dontwarn org.slf4j.**

# AndroidX DataStore & Biometric
-keep class androidx.datastore.** { *; }
-keep class androidx.biometric.** { *; }
