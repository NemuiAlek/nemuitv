plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The signing key lives outside the repo: its path comes from NEMUITV_STORE_FILE in ~/.gradle/gradle.properties
// (with the password and alias). Every build must be signed with the same key or TVs refuse updates.
val nemuiStoreFile: String? = providers.gradleProperty("NEMUITV_STORE_FILE").orNull

android {
    namespace = "com.nemuigames.nemuitv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nemuigames.nemuitv"
        minSdk = 31
        targetSdk = 37
        versionCode = 10001
        versionName = "1.0.1"
        // arm64 = emulator AVD; armeabi-v7a = Google TV Streamer (32-bit userspace on MT8696)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (nemuiStoreFile != null) create("nemui") {
            storeFile = file(nemuiStoreFile)
            storePassword = providers.gradleProperty("NEMUITV_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("NEMUITV_KEY_ALIAS").get()
            keyPassword = storePassword
        }
    }

    buildTypes {
        getByName("debug") {
            if (nemuiStoreFile != null) signingConfig = signingConfigs.getByName("nemui")
        }
        // What the TVs run: optimized (R8) and not debuggable, so Compose runs at full speed.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (nemuiStoreFile != null) signingConfig = signingConfigs.getByName("nemui")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.stream.webrtc)
    implementation(libs.stream.webrtc.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
