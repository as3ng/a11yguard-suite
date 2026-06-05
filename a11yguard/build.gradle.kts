plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fraudintel.a11yguard"
    compileSdk = 35

    defaultConfig {
        minSdk = 21 // works from Android 5.0 (old) through Android 15/16/17 (new)
        consumerProguardFiles("consumer-rules.pro")
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

dependencies {
    // Intentionally dependency-light to minimise our own attack surface and ease integration.
    implementation("androidx.annotation:annotation:1.8.0")
}
