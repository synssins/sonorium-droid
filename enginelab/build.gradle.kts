// Engine lab (shared/engine.md): sonorium.engine on the phone through Chaquopy, against a minimal
// Kotlin mixer, for CPU and battery measurements. A separate app; the Sonorium app doesn't include it.
plugins {
    alias(libs.plugins.android.application)
    id("com.chaquo.python") version "17.0.0"
}

android {
    namespace = "app.sonorium.enginelab"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.sonorium.enginelab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-lab"
        // Phones only (the emulators run the app, not the lab); keeps the APK small
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // The MediaCodec predecoder is shared with the app (engine.md 5a)
    sourceSets["main"].kotlin.directories.add("../app/src/main/java/app/sonorium/audio")
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip { install("numpy==1.26.2") }
    }
}
