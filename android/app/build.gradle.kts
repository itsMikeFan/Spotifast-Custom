plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.itsmikefan.spotifastcustom"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itsmikefan.spotifastcustom"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number, so each APK installs over the last.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // One committed debug key, so every CI build updates the installed
        // app instead of needing an uninstall (which loses the sign-in).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // HttpURLConnection cannot send a body with DELETE, which removing playlist songs needs.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Covers.
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    // Android's own org.json is a stub in unit tests.
    testImplementation("org.json:json:20240303")
}
