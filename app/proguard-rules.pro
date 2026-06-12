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
-keep class com.fossyaudio.bpcontrol.shared.model.FilterBand { *; }
-keep class com.fossyaudio.bpcontrol.shared.model.Preset { *; }

# ------------------------------------------------------------------
# 3. COMPOSE UI STABILITY
# ------------------------------------------------------------------
# Compose compiler handles stability annotations; no custom view inflation needed.
# Removed EqGraphView and EqAdapter rules (classes deleted — UI is now Compose).