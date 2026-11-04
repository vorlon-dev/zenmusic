// ZenMusic — root build configuration.
// Modules are published independently (see README for coordinates);
// the `zenmusic` module is the optional umbrella that wires everything.

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}

subprojects {
    // JitPack derives the group from the GitHub owner (com.github.<user>)
    // and rewrites whatever is set here; this value keeps local
    // publishToMavenLocal output stable.
    group = "com.github.vorlon-dev"
    version = "1.1.0"
}
