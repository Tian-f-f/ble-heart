plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.openhr.mihrbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openhr.mihrbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}