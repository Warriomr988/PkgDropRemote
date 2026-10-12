plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.panda.pkgdropremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.panda.pkgdropremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    // Solo core-ktx (NotificationCompat/ContextCompat).
    // UI con vistas clásicas del framework, HTTP con sockets crudos: sin más dependencias.
    implementation("androidx.core:core-ktx:1.13.1")
}
