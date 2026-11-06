plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val standaloneDiagnostic = providers.gradleProperty("standaloneDiagnostic").isPresent

android {
    namespace = "org.openhr.mihrbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = if (standaloneDiagnostic) "org.openhr.mihrbridge.diag" else "org.openhr.mihrbridge"
        manifestPlaceholders["appDisplayName"] = if (standaloneDiagnostic) "Mi HR Bridge 诊断版" else "Mi HR Bridge"
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