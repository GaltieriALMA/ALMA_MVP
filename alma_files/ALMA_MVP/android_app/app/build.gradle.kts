plugins {
    id("com.android.application")
}

android {
    namespace = "com.alma.mvp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alma.mvp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
