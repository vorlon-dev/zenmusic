# ZenMusic umbrella — consumer R8 rules.
#
# Keep ONLY the public facade; every engine-internal class is free to be
# renamed/shrunk/repackaged. Combined with the aggressive rules in
# proguard-rules.pro this yields zen.a.b.*-style layouts in release builds.
-keep public class com.zenmusic.ZenMusic { public *; }
-keep public class com.zenmusic.ZenEngine { public *; }
-keep public class com.zenmusic.AndroidTamperDetector { public *; }

# Keep the public API of every submodule (their own consumer rules also apply).
# ONLY facade classes — engine internals must stay obfuscatable.
-keep public class com.zenmusic.core.model.** { public *; }
-keep public class com.zenmusic.core.uri.ZenUri { public *; }
-keep public class com.zenmusic.core.uri.ZenUri$* { public *; }
-keep public class com.zenmusic.core.vault.ZenVault { public *; }
-keep public class com.zenmusic.core.log.ZenLog { public *; }
-keep public class com.zenmusic.core.decoy.ZenDecoys { public *; }
-keep public class com.zenmusic.core.ZenConfig { public *; }
-keep public class com.zenmusic.core.ZenConfig$* { public *; }
-keep public class com.zenmusic.core.Zen*Exception { public *; }
-keep public class com.zenmusic.potoken.PotokenCoordinator { public *; }
-keep public class com.zenmusic.potoken.PoToken { public *; }
-keep public class com.zenmusic.potoken.PoTokenType { public *; }
-keep public class com.zenmusic.potoken.PotokenProvider { public *; }
-keep public class com.zenmusic.potoken.PotokenStats { public *; }
-keep public class com.zenmusic.extractor.ZenExtractor { public *; }
-keep public class com.zenmusic.extractor.ZenExtractor$* { public *; }
-keep public class com.zenmusic.extractor.ZenSearchApi { public *; }
-keep public class com.zenmusic.extractor.ZenSearchApi$* { public *; }
-keep public class com.zenmusic.extractor.ZenRadioApi { public *; }
-keep public class com.zenmusic.extractor.ZenLyricsApi { public *; }
-keep public class com.zenmusic.extractor.StreamPlan { public *; }
-keep public class com.zenmusic.resolver.ZenResolver { public *; }
-keep public class com.zenmusic.resolver.MediaChannel { public *; }
-keep public class com.zenmusic.resolver.TamperDetector { public *; }
-keep public class com.zenmusic.resolver.TamperDetector$* { public *; }
-keep public class com.zenmusic.datasource.ZenDataSource { public *; }
-keep public class com.zenmusic.datasource.ZenDataSourceFactory { public *; }
-keep public class com.zenmusic.datasource.ZenMediaItems { public *; }

-keepclassmembers enum com.zenmusic.core.model.ZenQuality {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
