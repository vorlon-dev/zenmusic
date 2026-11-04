plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "com.zenmusic"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro", "proguard-rules.pro")
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
    api(project(":zen-core"))
    api(project(":zen-potoken"))
    api(project(":zen-extractor"))
    api(project(":zen-resolver"))
    api(project(":zen-datasource"))
    // Vendored zemer-cipher (GPL-3.0): WebView BotGuard PO tokens + cipher.
    implementation(project(":zen-cipher"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "zenmusic"
            afterEvaluate { from(components["release"]) }
        }
    }
}
