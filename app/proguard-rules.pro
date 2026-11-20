# --- LiteRT -------------------------------------------------------------------------------------
# The native runtime (libLiteRt.so / liblitert_jni.so) instantiates and calls these classes over
# JNI by name. Their bundled consumer rules only cover the legacy org.tensorflow.lite API.
-keep class com.google.ai.edge.litert.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# LiteRT's optional AI-pack provider references Play Asset Delivery, which is excluded.
-dontwarn com.google.android.play.core.**

# --- Size / speed -------------------------------------------------------------------------------
# Note: -repackageclasses breaks WorkManager's Room database lookup (pulled in transitively), so
# classes keep their packages.

# Strip verbose/debug/info logging from release builds (warnings and errors are kept).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# kotlinx.serialization and AndroidX ship their own consumer rules.
