import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The com.google.gms.google-services plugin HARD-FAILS the build if
// app/google-services.json is missing. The json is not in the repo yet (the
// user adds it later), so apply the plugin only when the file exists. Until
// then the app compiles + runs with Firebase Messaging present but inert
// (FirebaseApp never initializes, so SlidePushService is a no-op).
val googleServicesJson = project.file("google-services.json")
if (googleServicesJson.exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

// A debug build remains useful before Firebase is configured, but a release
// without this file can never receive closed-app calls. Refuse to create a
// silently broken production artifact.
val releaseRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}
if (releaseRequested && !googleServicesJson.exists()) {
    throw GradleException(
        "Release builds require android/app/google-services.json. " +
            "Register app.slide in Firebase and add the downloaded config; " +
            "without it FCM and background incoming calls are disabled."
    )
}
gradle.taskGraph.whenReady {
    val containsReleaseArtifact = allTasks.any { task ->
        task.project == project && task.name.contains("release", ignoreCase = true)
    }
    if (containsReleaseArtifact && !googleServicesJson.exists()) {
        throw GradleException(
            "Release task graph includes :app release work, but " +
                "android/app/google-services.json is missing. FCM is required."
        )
    }
}

// Optional release signing config from keystore.properties (gitignored).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "ai.exla.slide"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.slide"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        manifestPlaceholders["usesCleartextTraffic"] = "false"

        // 10.0.2.2 maps to the host machine's localhost from the emulator.
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/v1\"")
        buildConfigField("String", "WS_BASE_URL", "\"ws://10.0.2.2:8080/v1/ws\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // Local emulator development targets http://10.0.2.2. Production
            // uses HTTPS/WSS and must never permit accidental cleartext calls.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            // Production backend: slide-api on Fly (REST + signaling WS). NOT
            // App Runner — its Envoy ingress 403s WebSocket upgrades, so /v1/ws
            // (call ring + presence) can't connect there. Fly serves WebSockets.
            buildConfigField("String", "API_BASE_URL", "\"https://slide-api.fly.dev/v1\"")
            buildConfigField("String", "WS_BASE_URL", "\"wss://slide-api.fly.dev/v1/ws\"")
            // Minification is off for the first store submission: R8 strict mode
            // trips on optional Play Core / WebRTC classes that need keep-rules.
            // The app ships unminified (Play accepts this); revisit with proper
            // proguard-rules.pro before optimizing size.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Secure token storage
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    // Images
    implementation(libs.coil.compose)

    // LiveKit Android SDK (bundles its own WebRTC under org.webrtc). Replaces the
    // standalone io.github.webrtc-sdk dep + the custom-SFU client.
    implementation(libs.livekit.android)

    // Firebase Cloud Messaging (push). The dependency is always present so the
    // app compiles; FirebaseApp only initializes once google-services.json + the
    // google-services plugin are in place (see conditional apply above).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation("junit:junit:4.13.2")
}
