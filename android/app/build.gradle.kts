import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Secrets stay in android/local.properties (gitignored). Never hardcode keys.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val amapKey: String = localProperties.getProperty("amap.key")?.trim().orEmpty()

android {
    namespace = "com.example.ninebotplus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ninebotplus"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.2.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // Injected into AndroidManifest meta-data for AMap Android Map SDK.
        manifestPlaceholders["amapKey"] = amapKey
        buildConfigField("String", "AMAP_KEY", "\"$amapKey\"")
        // Personal debug login prefill — values live only in local.properties.
        fun lp(key: String): String =
            localProperties.getProperty(key)?.trim().orEmpty()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        buildConfigField("String", "NPP_DEFAULT_SERVER", "\"${lp("npp.server")}\"")
        buildConfigField("String", "NPP_DEFAULT_BEARER", "\"${lp("npp.bearer")}\"")
        buildConfigField("String", "NPP_DEFAULT_PHONE", "\"${lp("npp.phone")}\"")
        buildConfigField("String", "NPP_DEFAULT_PASSWORD", "\"${lp("npp.password")}\"")
        // Optional FCM via local.properties (self-hosted Firebase project).
        // Prefer android/app/google-services.json when present; these fields
        // enable FirebaseApp manual init as a drop-in alternative.
        buildConfigField("String", "FCM_API_KEY", "\"${lp("firebase.api_key")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${lp("firebase.app_id")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${lp("firebase.project_id")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${lp("firebase.gcm_sender_id")}\"")
        buildConfigField("String", "FCM_DEFAULT_WEB_CLIENT_ID", "\"${lp("firebase.default_web_client_id")}\"")
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("NINEPLUS_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("NINEPLUS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NINEPLUS_KEY_ALIAS")
                keyPassword = System.getenv("NINEPLUS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // Cleartext to LAN allowed via src/debug/res/xml/network_security_config.xml
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to debug signing for a release artifact.
            // Without NINEPLUS_KEYSTORE_PATH the APK is unsigned and must be
            // signed separately (apksigner) before distribution.
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.amap.map3d)
    implementation(libs.coil.compose)
    implementation(libs.androidx.security.crypto)
    // Optional FCM: compiles without google-services.json; tokens arrive only
    // when a Firebase project is configured in the consumer build.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Standard FCM path: drop google-services.json into android/app/ (gitignored)
// and the plugin generates Firebase resources. Without the file the build
// stays green; PushManager can still init Firebase from local.properties.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
