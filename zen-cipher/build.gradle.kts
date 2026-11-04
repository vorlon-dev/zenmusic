// zen-cipher — vendored copy of ZemerTeam/zemer-cipher (GPL-3.0).
// Provides WebView-based BotGuard PO-token minting + signature decipher with
// remote self-healing player configs (assets ship bundled; refreshed at
// runtime from upstream master). See ZEMER-CIPHER-LICENSE / README.
plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    `maven-publish`
}

android {
    namespace = "com.zemer.cipher"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.9.1")
    implementation("androidx.collection:collection-ktx:1.4.5")
    implementation("androidx.annotation:annotation:1.9.1")
    // zemer logs through Timber (compileOnly upstream). Timber with no planted
    // tree is a no-op — we ship it so consumers never need to wire anything.
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "zen-cipher"
            afterEvaluate { from(components["release"]) }
        }
    }
}
