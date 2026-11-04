# ZenMusic zen-datasource — consumer R8 rules.
-keep public class com.zenmusic.datasource.ZenDataSourceFactory { public *; }
-keep public class com.zenmusic.datasource.ZenDataSource { public *; }
-keep public class com.zenmusic.datasource.ZenMediaItems { public *; }

# Media3 is reflective on interfaces
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
