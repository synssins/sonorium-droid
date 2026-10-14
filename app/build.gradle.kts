import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // The theme engine (sonorium.engine, Python) for playing themes on the phone
    id("com.chaquo.python") version "17.0.0"
}

// Release signing comes from environment variables (CI) or an untracked
// keystore.properties (local builds). Without either, release builds are unsigned.
val signing: Map<String, String?> = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
    fun pick(env: String, prop: String) = System.getenv(env) ?: props.getProperty(prop)
    mapOf(
        "storeFile" to pick("SONORIUM_KEYSTORE_FILE", "storeFile"),
        "storePassword" to pick("SONORIUM_KEYSTORE_PASSWORD", "storePassword"),
        "keyAlias" to pick("SONORIUM_KEY_ALIAS", "keyAlias"),
        "keyPassword" to pick("SONORIUM_KEY_PASSWORD", "keyPassword"),
    )
}
val hasSigning = signing.values.all { !it.isNullOrBlank() }

android {
    namespace = "app.sonorium"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.sonorium"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("SONORIUM_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("SONORIUM_VERSION_NAME") ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The engine's Python and numpy are per ABI: phones (arm64) and the CI emulators (x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
            // Google Play: native symbols in the bundle (for crash reports of the engine's native libraries)
            debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signing.getValue("storeFile")!!)
                storePassword = signing["storePassword"]
                keyAlias = signing["keyAlias"]
                keyPassword = signing["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // Shrunk exactly like release, signed with the debug key, installable beside the debug app: CI plays a
        // theme with it, so code reached only by reflection (Python) and removed by R8 shows up before a release
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.gms.code.scanner)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    // Google Cast (CastContext, MediaRouter) for casting the phone's own mix
    implementation(libs.media3.cast)
    // Google Play Billing: the Pro purchase in release builds (test builds unlock locally)
    implementation(libs.play.billing)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.web)
    androidTestImplementation(libs.uiautomator)
}

// The theme engine: the same files as Engine Lab (engine-core), plus the app's own calls (src/main/python)
chaquopy {
    defaultConfig {
        version = "3.12"
        pip { install("numpy==1.26.2") }
    }
    sourceSets {
        getByName("main") { srcDir("../enginelab/src/main/python") }
    }
}
