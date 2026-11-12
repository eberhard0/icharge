plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.iameberhard.icharge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iameberhard.icharge"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number / tag through the environment; local builds fall back to 1 / 1.0.0.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        // The owner's own Open Charge Map key (repo secret OCM_API_KEY) so the app can refresh the
        // station list by itself. Empty = refresh needs a key typed into Settings.
        buildConfigField("String", "OCM_API_KEY", "\"${(System.getenv("OCM_API_KEY") ?: "").trim()}\"")
        // Google Maps Platform key (repo secret GOOGLE_MAPS_KEY): the map with live traffic, and the
        // Routes API for traffic-aware trip times. Restricted to this package + signing certificate.
        val mapsKey = (System.getenv("GOOGLE_MAPS_KEY") ?: "").trim()
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
        buildConfigField("String", "GOOGLE_MAPS_KEY", "\"$mapsKey\"")
    }

    // Upload key supplied by CI through the environment (KEYSTORE_FILE, KEYSTORE_PASSWORD,
    // KEY_ALIAS, KEY_PASSWORD); local builds stay unsigned.
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    // Two editions from one code base: "google" (Google map with live traffic + Routes API, needs the
    // owner's key) and "open" (OpenStreetMap tiles via MapLibre, no account, for anyone).
    flavorDimensions += "maps"
    productFlavors {
        create("google") {
            dimension = "maps"
            isDefault = true
        }
        create("open") {
            dimension = "maps"
            applicationIdSuffix = ".open"
            resValue("string", "app_name", "iCharge Open")
            // MapLibre's native renderer is ~13 MB per CPU type; current phones are 64-bit.
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    implementation(libs.car.app)
    implementation(libs.work.runtime.ktx)
    implementation(libs.security.crypto)
    "googleImplementation"(libs.play.services.maps)
    "googleImplementation"(libs.maps.compose)
    "openImplementation"(libs.maplibre.android)
}
