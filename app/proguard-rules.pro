# TunerMap Pro — R8 / ProGuard rules
# Only keep what the runtime needs reflectively; everything else is shrunk.
# No app logic (esp. LiveMapStore / MapBinning / LPGAnalyzer / FuelMapView /
# DerivedSensors) is affected — R8 only removes unreachable code.

# Keep line numbers for deobfuscated Play/console crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AndroidX / Material (reflection-free, but keep annotations + ctor safety)
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# NanoHTTPD — HTTP server, keep its public API surface.
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# usb-serial-for-android — driver discovery via reflection on some builds.
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**

# All app classes are kept as entry points (activities/services) and the
# bulk of logic is retained; we only shrink clearly-unused library code.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.core.app.ActivityCompat
-keep class com.alpha.obd2logger.** { *; }

# org.json is used directly (no Gson) — no model classes to keep.

# Retain parcelables / serializables if any.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
