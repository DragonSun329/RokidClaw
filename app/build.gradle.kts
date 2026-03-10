plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dragon.rokidclaw"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dragon.rokidclaw"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // OpenClaw Gateway URL - change to your actual gateway address
        buildConfigField("String", "OPENCLAW_GATEWAY_URL", "\"http://127.0.0.1:3771\"")
        buildConfigField("String", "OPENCLAW_TOKEN", "\"\"") // Set via local.properties
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Rokid CXR-S SDK
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20250519.061355-45")

    // Android core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
