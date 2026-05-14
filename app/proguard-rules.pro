# ------------------------------------------------------------------
# 1. CRASH REPORTING & DEBUGGING (CRITICAL FOR USB/HARDWARE)
# ------------------------------------------------------------------
# Keeps line numbers intact so your Play Console crash stack traces
# actually tell you where the USB connection failed.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------
# 2. DATA MODELS (CRITICAL FOR JSON PREFS & AUTOEQ)
# ------------------------------------------------------------------
# Protects the data classes from being mangled. Since you rely on
# .copy() and manual JSON parsing, it's safest to keep the signatures intact.
-keep class com.chesaudio.bpcontrol.MainActivity$FilterBand { *; }
-keep class com.chesaudio.bpcontrol.MainActivity$Preset { *; }

# ------------------------------------------------------------------
# 3. CUSTOM VIEWS (CRITICAL FOR XML INFLATION)
# ------------------------------------------------------------------
# If R8 obfuscates the name of your custom graph view, the layout inflater
# will throw a ClassNotFoundException on app launch.
-keep public class com.chesaudio.bpcontrol.EqGraphView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ------------------------------------------------------------------
# 4. RECYCLERVIEW ADAPTER SAFETY
# ------------------------------------------------------------------
# Prevents R8 from aggressively stripping View references inside your ViewHolder.
-keepclassmembers class com.chesaudio.bpcontrol.MainActivity$EqAdapter$ViewHolder {
    <fields>;
}