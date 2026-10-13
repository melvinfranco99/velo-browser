plugins {
    id("com.android.application")
}

val geckoviewVersion = "158.0.20261007201254"

android {
    namespace = "com.melvin.velo"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.melvin.velo"
        minSdk = 29
        targetSdk = 36
        versionName = "2.0.0"
    }

    // Un APK por arquitectura: GeckoView pesa ~90 MB por arquitectura y un APK
    // universal superaría los 200 MB.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            versionCode = 2002
            ndk { abiFilters += "arm64-v8a" }
        }
        create("arm32") {
            dimension = "abi"
            versionCode = 2001
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("x64") {
            dimension = "abi"
            versionCode = 2003
            ndk { abiFilters += "x86_64" }
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("info.guardianproject:tor-android:0.4.8.16")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    // Sin Google Play Services: GeckoView solo los usa para WebAuthn, que Velo desactiva.
    fun gecko(abi: String) = "org.mozilla.geckoview:geckoview-$abi:$geckoviewVersion"
    "arm64Implementation"(gecko("arm64-v8a")) { exclude(group = "com.google.android.gms") }
    "arm32Implementation"(gecko("armeabi-v7a")) { exclude(group = "com.google.android.gms") }
    "x64Implementation"(gecko("x86_64")) { exclude(group = "com.google.android.gms") }
}
