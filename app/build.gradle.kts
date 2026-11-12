plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.princeps.spotifyadexit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.princeps.spotifyadexit"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Intentionally no third-party/AndroidX libraries.
    // Everything is built on the plain Android platform SDK + Kotlin stdlib
    // so the app has the smallest possible dependency surface and needs
    // nothing beyond the Android Gradle Plugin + Kotlin plugin to build.
}
