plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.frankenkitten42.claudewidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.frankenkitten42.claudewidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
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
    // WorkManager — periodic background file reads
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.12.0")

    // AppCompat — required for AppCompatActivity
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Material3 — required for Theme.Material3
    implementation("com.google.android.material:material:1.11.0")
}
