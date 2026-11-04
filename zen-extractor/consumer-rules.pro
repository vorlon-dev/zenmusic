# ZenMusic zen-extractor — consumer R8 rules.
# Facade classes only; InnerTube internals (Parsers, JsonWalk, ZenInnertube)
# stay obfuscatable.
-keep public class com.zenmusic.extractor.ZenExtractor { public *; }
-keep public class com.zenmusic.extractor.ZenExtractor$* { public *; }
-keep public class com.zenmusic.extractor.ZenSearchApi { public *; }
-keep public class com.zenmusic.extractor.ZenSearchApi$* { public *; }
-keep public class com.zenmusic.extractor.ZenRadioApi { public *; }
-keep public class com.zenmusic.extractor.ZenLyricsApi { public *; }
-keep public class com.zenmusic.extractor.StreamPlan { public *; }

# org.json is reflectively parsed
-keep class org.json.** { *; }
-dontwarn org.json.**
-dontwarn okhttp3.**
-dontwarn okio.**
