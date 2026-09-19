plugins {
    id("com.android.application")
}
val almaBaseUrl = System.getenv("CODESPACE_NAME")?.let { "https://$it-8000.app.github.dev" } ?: "http://10.0.2.2:8000"
val almaClientToken = System.getenv("ALMA_CLIENT_TOKEN") ?: ""

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
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ALMA_BASE_URL", "\"$almaBaseUrl\"")
        buildConfigField("String", "ALMA_CLIENT_TOKEN", "\"$almaClientToken\"")
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
