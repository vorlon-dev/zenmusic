# ZenMusic zen-core — consumer R8 rules (applied when the APP builds).
#
# Public API of zen-core is reflective-free and kept by name so app code
# compiles against stable symbols; everything else inside the library is
# free to be renamed/shrunk by R8 full mode.

-keep public class com.zenmusic.core.model.** { public *; }
-keep public class com.zenmusic.core.uri.ZenUri { public *; }
-keep public class com.zenmusic.core.uri.ZenUri$* { public *; }
-keep public class com.zenmusic.core.vault.ZenVault { public *; }
-keep public class com.zenmusic.core.log.ZenLog { public *; }
-keep public class com.zenmusic.core.decoy.ZenDecoys { public *; }
-keep public class com.zenmusic.core.ZenConfig { public *; }
-keep public class com.zenmusic.core.ZenConfig$* { public *; }
-keep public class com.zenmusic.core.ZenException { public *; }
-keep public class com.zenmusic.core.ZenResolutionException { public *; }
-keep public class com.zenmusic.core.ZenTokenException { public *; }
-keep public class com.zenmusic.core.ZenVaultException { public *; }

-keepclassmembers enum com.zenmusic.core.model.ZenQuality {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# GCM crypto must not be stripped/reordered
-keep class javax.crypto.** { *; }
-dontwarn javax.naming.**
