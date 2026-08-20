// Standalone root build for the PUBLIC MIRROR repo — single-module Android
// library with maven-publish so JitPack can build + publish the AAR.
plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("maven-publish")
}

android {
    namespace = "com.notibase.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // The ONLY runtime dependency (Arch §8.2).
    api("com.google.firebase:firebase-messaging:24.1.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.notibaseorg"
                artifactId = "notibase-android"
                version = project.findProperty("version")?.toString() ?: "0.0.0-local"
            }
        }
    }
}
