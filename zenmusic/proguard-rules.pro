# ZenMusic — aggressive engine hardening rules (applied when the HOST APP
# builds its release APK/AAB; library AARs are transformed there).
#
# Full-mode R8: rename everything that is not explicitly kept, repackage into
# meaningless packages, strip attributes and logging, and keep only what the
# reflectively-touched internals (Rhino VM, media3, crypto) require.

# ---- global obfuscation posture ------------------------------------------
-repackageclasses 'zen.a.b'
-allowaccessmodification
-overloadaggressively
-adaptclassstrings
-adaptresourcefilenames
-adaptresourcefilecontents

# minimal attributes (kotlin coroutines + media3 need these)
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# ---- strip logging --------------------------------------------------------
# (ZenLog.d/w are no-ops unless ZenLog.enabled was set at runtime; R8 removes
# even the call sites so no scrubbed-but-real strings survive in bytecode.)
-assumenosideeffects class com.zenmusic.core.log.ZenLog {
    public void d(java.lang.String);
    public void d(kotlin.jvm.functions.Function0);
    public void w(java.lang.String, java.lang.Throwable);
    public void w(java.lang.String);
}

# ---- rename dictionaries (optional) ---------------------------------------
# For junk-identifier renaming, copy proguard-dict.txt next to YOUR app's
# rules file and reference it there (paths resolve from the app module):
#   -obfuscationdictionary proguard-dict.txt
#   -classobfuscationdictionary proguard-dict.txt
#   -packageobfuscationdictionary proguard-dict.txt
# Default full-mode R8 naming (a, b, c… + -repackageclasses) is already strong.

# ---- crypto must not be stripped ------------------------------------------
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
-dontwarn javax.crypto.**
-dontwarn javax.naming.**

# ---- Rhino runs the BotGuard VM ------------------------------------------
-keep class org.mozilla.javascript.** { *; }
-keepclassmembers class * extends org.mozilla.javascript.ScriptableObject { *; }
-dontwarn org.mozilla.javascript.**
-keepclassmembers class com.zenmusic.potoken.js.** { public *; }
-keep class com.zenmusic.potoken.js.** { *; }

# ---- org.json / OkHttp / Coroutines --------------------------------------
-keep class org.json.** { *; }
-dontwarn org.json.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- media3 ---------------------------------------------------------------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

-dontnote **
