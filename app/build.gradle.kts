plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tanuj.applock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tanuj.applock"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    // Fixed signing key so every new build installs as an UPDATE (keeps your PIN & locked apps).
    // Keep the repo private: anyone with this key could sign an "update" of your app.
    signingConfigs {
        create("fixed") {
            storeFile = file("applock.jks")
            storePassword = "applock123"
            keyAlias = "applock"
            keyPassword = "applock123"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
