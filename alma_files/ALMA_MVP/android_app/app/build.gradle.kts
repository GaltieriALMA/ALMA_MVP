plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val almaBaseUrl = "https://alma-mvp.onrender.com"

android {
    namespace = "com.alma.mvp"
    compileSdk = 35
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.alma.mvp"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "0.6.1"
        buildConfigField("String", "ALMA_BASE_URL", "\"$almaBaseUrl\"")
        manifestPlaceholders["usesCleartext"] = "false"
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartext"] = "true"
        }
        getByName("release") {
            manifestPlaceholders["usesCleartext"] = "false"
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
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.google.android.filament:gltfio-android:1.75.1")
    implementation("com.google.android.filament:filament-utils-android:1.75.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
implementation("com.alphacephei:vosk-android:0.3.75")
}
