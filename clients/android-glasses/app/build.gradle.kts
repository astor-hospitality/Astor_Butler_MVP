plugins {
    id("com.android.application")
}

android {
    namespace = "com.astor.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.astor.glasses.android"
        // Android 12: one way to pick the headset microphone, no legacy Bluetooth audio calls.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core"))
    // Only for FileProvider: the camera application writes the photo into our private cache.
    implementation("androidx.core:core:1.13.1")
}
