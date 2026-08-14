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
        versionCode = 22
        versionName = "0.1.21"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
