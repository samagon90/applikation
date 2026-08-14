plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.arena.webapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.arena.webapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/release.keystore")
            storePassword = "arenaai123"
            keyAlias = "arena"
            keyPassword = "arenaai123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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

dependencies {
    implementation("androidx.core:core:1.9.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // ProxyController — официальный API прокси для WebView (Android 10+).
    // Нужен для встроенного автоматического прокси.
    implementation("androidx.webkit:webkit:1.9.0")
}
