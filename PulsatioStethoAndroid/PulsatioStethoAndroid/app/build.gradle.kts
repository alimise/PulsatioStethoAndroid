plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pulsatio.stetho"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pulsatio.stetho"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
// Harici kutuphane yok: sadece Android SDK + Kotlin standart kutuphanesi
