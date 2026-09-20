plugins {
    id("com.android.application")
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
        versionCode = 2
        versionName = "0.2.0"
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
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
