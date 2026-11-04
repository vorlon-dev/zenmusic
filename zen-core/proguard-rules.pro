# zen-core local build rules (library AAR/JAR obfuscation happens in the app
# build via consumer-rules.pro; these harden test/JVM artifacts).
-dontwarn java.lang.invoke.**
-dontwarn org.slf4j.**
