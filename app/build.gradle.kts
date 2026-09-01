plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iqoo.perfcollect"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iqoo.perfcollect"
        minSdk = 26
        targetSdk = 36
        versionCode = 104
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("app/perfcollect-release.jks")
            storePassword = providers.gradleProperty("STORE_PASSWORD").getOrElse("")
            keyAlias = "perfcollect"
            keyPassword = providers.gradleProperty("KEY_PASSWORD").getOrElse("")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
}