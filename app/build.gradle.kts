plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.camstitch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.camstitch"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-poc"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug signing so the release APK installs without extra setup (POC only).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party dependencies: Camera2, MediaCodec, NsdManager, GLES20 and org.json are all in the platform.
