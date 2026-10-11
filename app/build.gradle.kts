import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
