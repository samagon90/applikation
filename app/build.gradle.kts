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
        versionCode = 7
        versionName = "3.0.0"
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
        // Совместимость с оффлайн-сборкой через dx: лямбды классами, а не
        // invokedynamic (dx не умеет desugar invoke-custom, ART его не исполняет).
        freeCompilerArgs += listOf("-Xlambdas=class", "-Xsam-conversions=class")
    }
}

dependencies {
    // Приложение не использует сторонних библиотек: только платформенные API.
    // Это позволяет собирать APK полностью оффлайн (см. scripts/offline_build.sh).
}
