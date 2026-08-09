# R8 rules for DeutschFlow.
#
# Room, Hilt, Glance, DataStore and ML Kit all ship consumer rules, so this file
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

# --- Gemini client -----------------------------------------------------------
# com.google.ai.client.generativeai serialises its request/response models with
# kotlinx.serialization, which resolves serializers reflectively by class.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.google.ai.client.generativeai.**$$serializer { *; }
-keepclassmembers class com.google.ai.client.generativeai.** {
    *** Companion;
}

# --- Diagnostics -------------------------------------------------------------
# Keep line numbers so release stack traces stay readable, but hide the original
# source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
