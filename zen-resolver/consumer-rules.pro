# ZenMusic zen-resolver — consumer R8 rules.
-keep public class com.zenmusic.resolver.ZenResolver { public *; }
-keep public class com.zenmusic.resolver.MediaChannel { public *; }
-keep public class com.zenmusic.resolver.TamperDetector { public *; }

# OkHttp reflectively loads platform TLS providers
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
