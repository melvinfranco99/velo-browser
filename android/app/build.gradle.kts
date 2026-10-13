plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.melvin.velo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.melvin.velo"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // ARM (casi todos los móviles) y x86_64 (emuladores / Chromebooks)
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            val ks = file(System.getenv("VELO_KEYSTORE") ?: "${rootDir}/keystore/velo-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("VELO_KEYSTORE_PASSWORD")
                keyAlias = "velo"
                keyPassword = System.getenv("VELO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("info.guardianproject:tor-android:0.4.8.16")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
}
