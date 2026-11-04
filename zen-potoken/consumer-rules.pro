# ZenMusic zen-potoken — consumer R8 rules.
-keep public class com.zenmusic.potoken.PotokenCoordinator { public *; }
-keep public class com.zenmusic.potoken.PoToken { public *; }
-keep public class com.zenmusic.potoken.PoTokenType { public *; }
-keep public class com.zenmusic.potoken.PotokenProvider { public *; }
-keep public class com.zenmusic.potoken.PotokenStats { public *; }

# Rhino runs BotGuard bytecode — reflective script access must survive.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.junit.**

# The VM script accesses public members of our JS shim classes reflectively.
-keepclassmembers class com.zenmusic.potoken.js.* { public *; }
-keep class com.zenmusic.potoken.js.* { *; }
