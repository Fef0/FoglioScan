plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.foglioscan"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.foglioscan"
        minSdk = 29
        targetSdk = 37
        versionCode = 6
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One APK per CPU architecture for side-loading: arm64-v8a for phones, x86_64 for the
    // emulator. (Play App Bundles split automatically.)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // Release key from ~/.gradle/gradle.properties (foglioscan.keystore, .keystore.password,
    // .key.alias), never from the repository. Without it, release builds fall back to the
    // debug key so anyone can still build and side-load.
    val releaseKeystore = providers.gradleProperty("foglioscan.keystore").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("foglioscan.keystore.password").get()
                keyAlias = providers.gradleProperty("foglioscan.key.alias").getOrElse("foglioscan")
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Real-photo benchmark data (tools/fetch_smartdoc.py) goes into the *test* APK only.
    sourceSets["androidTest"].assets.directories += rootProject.file("testdata").path

    androidResources {
        localeFilters += listOf("en", "it")
        // Models are memory-mapped by LiteRT, so they must be stored uncompressed.
        noCompress += "tflite"
    }
}

// LiteRT's optional Play "AI pack" model delivery drags in WorkManager + Room + Play Core and
// starts them at launch. The model ships inside the APK, so it's excluded everywhere.
configurations.configureEach {
    exclude(group = "com.google.android.play", module = "asset-delivery")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Installs the bundled Baseline Profile (AOT-compiled startup & camera paths).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.datastore.preferences)

    // CameraX + the Compose-native viewfinder.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    // Manufacturer HDR / Auto processing (Camera Extensions) inside the in-app camera.
    implementation(libs.camerax.extensions)

    // LiteRT (formerly TensorFlow Lite) CompiledModel API with GPU acceleration, for the
    // neural document detector.
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    // On-device OCR: ML Kit Text Recognition v2, Latin model bundled (offline, no Play services).
    implementation(libs.mlkit.text.recognition)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Compose testing pulls Espresso 3.5, which uses a hidden API removed in Android 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
}
