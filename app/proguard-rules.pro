# R8 rules for DeutschFlow.
#
# Room, Hilt, Glance and DataStore all ship consumer rules, so this file
# only covers what is specific to this app. Additional keep rules also live in
# src/main/keepRules/ and are merged in by AGP.

# --- Privacy -----------------------------------------------------------------
# Recognition results must never reach a release log. The call sites no longer
# log transcript content, and this strips debug/verbose logging outright so a
# future careless Log.d cannot leak speech either.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# --- Room entities -----------------------------------------------------------
# Entities are constructed reflectively by generated DAO code.
-keep class com.aus.deutschflow.data.local.entities.** { *; }

# --- AI client ---------------------------------------------------------------
# Nothing to keep. GroqHelper builds its request with org.json and reads the reply
# by hand, so no model class is resolved reflectively. The rules that used to live
# here existed for the Gemini SDK's kotlinx.serialization models, and went with it.

# --- Diagnostics -------------------------------------------------------------
# Keep line numbers so release stack traces stay readable, but hide the original
# source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
