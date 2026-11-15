import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "dev.alexaero.tvlock"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.alexaero.tvlock"
        // Android TV 7.0+ (older boxes are still common).
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.4.0"
    }

    // Two APKs from one codebase, same app ID and key: the TV one has the lock; the phone one is only the
    // remote/setup companion with no sensitive permissions (so phones and Play Protect don't flag it).
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            buildConfigField("boolean", "TV", "true")
        }
        create("phone") {
            dimension = "device"
            buildConfigField("boolean", "TV", "false")
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Quick builds for testing: like release (same key, so it installs over the TV's copy) but without R8.
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Phone remote: tiny HTTP server on the TV + QR code for pairing.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    // Phone companion: ADB client for automatic TV setup.
    implementation("dev.mobile:dadb:1.2.10")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
