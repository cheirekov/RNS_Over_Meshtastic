plugins {
    id("com.android.application")
}

fun requiredSigningEnvironment(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is required for a signed release")

android {
    namespace = "bg.reticulum.meshtastic.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "bg.reticulum.meshtastic.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val keystore = System.getenv("ANDROID_KEYSTORE_FILE")
        if (!keystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = requiredSigningEnvironment("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = requiredSigningEnvironment("ANDROID_KEY_ALIAS")
                keyPassword = requiredSigningEnvironment("ANDROID_KEY_PASSWORD")
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

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (System.getenv("ANDROID_KEYSTORE_FILE").isNullOrBlank()) {
            error(
                "Release signing is mandatory. Set ANDROID_KEYSTORE_FILE, " +
                    "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD. " +
                    "See android/README.md."
            )
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
