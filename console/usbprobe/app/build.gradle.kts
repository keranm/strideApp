plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.stride.usbprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.stride.usbprobe"
        minSdk = 24
        // Console is Android 9. Targeting 28 avoids newer background/permission
        // restrictions we have no use for on a single-purpose kiosk device.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1-probe"
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

// No dependencies on purpose — plain framework Activity keeps the build fast
// and the APK tiny. This is a throwaway diagnostic, not the HUD.
dependencies {}
