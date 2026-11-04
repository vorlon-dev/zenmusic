plugins {
    `java-library`
    kotlin("jvm")
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    api(project(":zen-core"))
    api(project(":zen-extractor"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "zen-resolver"
            from(components["java"])
        }
    }
}
