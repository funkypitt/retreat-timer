plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.freedomfighter.retreattimer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.freedomfighter.retreattimer"
        // Android 8 by default. `-PminSdk=24` builds the "android7" variant for old
        // tablets (Galaxy Tab A 2015 stops at Android 7.1); it is the same app, the
        // API 26+ calls are all guarded, and it is only ever sent by hand.
        minSdk = (project.findProperty("minSdk") as String?)?.toInt() ?: 26
        targetSdk = 36
        versionCode = 24
        versionName = "1.14.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
