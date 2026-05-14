# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# ------------------------------------------------------------------
# 1. HARDWARE & CRASH DEBUGGING (CRITICAL FOR USB APPS)
# ------------------------------------------------------------------
# Keep line numbers and file names so USB exception stack traces are readable
-keepattributes SourceFile,LineNumberTable
# Optional: Rename the source file attribute to something generic if you want slightly more obfuscation
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------
# 2. DATA CLASS PROTECTION
# ------------------------------------------------------------------
# Protects the FilterBand data class, its constructor, and synthetic methods like copy()
-keep class com.chesaudio.bpcontrol.MainActivity$FilterBand {
    *;
}

# ------------------------------------------------------------------
# 3. RECYCLERVIEW & ADAPTER PROTECTION
# ------------------------------------------------------------------
# Prevent R8 from stripping the View references inside the ViewHolder
-keepclassmembers class com.chesaudio.bpcontrol.MainActivity$EqAdapter$ViewHolder {
    <fields>;
}

## ------------------------------------------------------------------
## 4. BROADCAST RECEIVER SAFETY
## ------------------------------------------------------------------
## Ensures the dynamic USB attach/detach receiver isn't mangled or stripped
#-keepnames class * extends android.content.BroadcastReceiver
#-keepclassmembers class * extends android.content.BroadcastReceiver {
#    public void onReceive(android.content.Context, android.content.Intent);
#}
#
## ------------------------------------------------------------------
## 5. UI COMPONENT SAFETY (findViewById)
## ------------------------------------------------------------------
## Ensure that all Views instantiated from XML layouts via ID keep their standard Android signatures
#-keepclassmembers class * extends androidx.appcompat.app.AppCompatActivity {
#    public void *(android.view.View);
#}
#-keep public class * extends android.view.View {
#    public <init>(android.content.Context);
#    public <init>(android.content.Context, android.util.AttributeSet);
#    public <init>(android.content.Context, android.util.AttributeSet, int);
#    public void set*(...);
#}

# ------------------------------------------------------------------
# 6. FUTURE-PROOFING: JNI / C++ (If you offload math later)
# ------------------------------------------------------------------
# Uncomment if you move the PEQ math to a C++ .so library
#-keepclasseswithmembernames class * {
#    native <methods>;
#}

## Prevent R8 from stripping or renaming your data models
#-keep class com.chesaudio.bpcontrol.MainActivity$FilterBand { *; }
#-keep class com.chesaudio.bpcontrol.MainActivity$Preset { *; }
#
## If you use the graph, keep the custom view
#-keep class com.chesaudio.bpcontrol.EqGraphView { *; }

# 1. Protect the Data Models in MainActivity
# These are essential for the AutoEQ parser and hardware logic
-keep class com.chesaudio.bpcontrol.MainActivity$FilterBand { *; }
-keep class com.chesaudio.bpcontrol.MainActivity$Preset { *; }

# 2. Keep Coroutines and Regex logic intact
# R8 sometimes over-optimizes the Regex matching used in parseAutoEq
-keep class kotlinx.coroutines.** { *; }
-keepnames class ** {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# 3. Prevent stripping of string-based logic
-keepclassmembernames class * {
    java.lang.String *;
}