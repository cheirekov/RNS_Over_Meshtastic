plugins {
    id("com.android.application")
}

android {
    namespace = "bg.reticulum.meshtastic.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "bg.reticulum.meshtastic.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val keystore = System.getenv("ANDROID_KEYSTORE_FILE")
        if (!keystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    ?: error("ANDROID_KEYSTORE_PASSWORD is required for a signed release")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    ?: error("ANDROID_KEY_ALIAS is required for a signed release")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                    ?: error("ANDROID_KEY_PASSWORD is required for a signed release")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
