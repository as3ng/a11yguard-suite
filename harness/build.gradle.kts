plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// AUTHORIZED TEST INSTRUMENTATION ONLY.
// This module is a controlled accessibility-automation rig used to validate the
// a11yguard detection engine against the client's own build during an authorized
// engagement. It is not a malware sample: no covert install, no persistence, no
// self-propagation, no remote C2, no credential exfiltration, no third-party targets.
android {
    namespace = "com.fraudintel.harness"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fraudintel.harness"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-poc"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
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
}

dependencies {
    implementation(project(":a11yguard"))
    implementation("androidx.appcompat:appcompat:1.7.0")
}
