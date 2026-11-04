plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marcosvec.pcwake"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marcosvec.pcwake"
        minSdk = 28          // Fire OS 7+
        targetSdk = 30
        versionCode = 6
        versionName = "0.5.0"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

configurations.configureEach {
    exclude(group = "org.json", module = "json")
}

dependencies {
    implementation(project(":core"))
}
