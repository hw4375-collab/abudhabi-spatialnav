import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * The ARCore API key enables Cloud Anchors. It is a per-developer credential and never
 * belongs in git, so it is read from `local.properties` (git-ignored) or the ARCORE_API_KEY
 * environment variable. Without it the app still runs and falls back to the manual-origin
 * spatial reference.
 */
fun secret(propertyName: String, environmentName: String): String {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use(::load) }.getProperty(propertyName)
    } else {
        null
    }
    return fromFile ?: System.getenv(environmentName) ?: ""
}

val arcoreApiKey: String = secret("arcore.apiKey", "ARCORE_API_KEY")

/**
 * Hackathon demo configuration only: a key baked into the APK is extractable, so production
 * must route the call through a backend proxy that holds the key.
 */
val openAiApiKey: String = secret("openai.apiKey", "OPENAI_API_KEY")

android {
    namespace = "com.hackson.spatialnav"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hackson.spatialnav"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        manifestPlaceholders["arcoreApiKey"] = arcoreApiKey
        buildConfigField("boolean", "ARCORE_API_KEY_CONFIGURED", arcoreApiKey.isNotEmpty().toString())
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testOptions {
        // RoomStore logs through android.util.Log; returning defaults keeps it JVM-testable
        // without dragging in Robolectric.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // L1 localization: VIO pose, depth, and (later) Cloud Anchors.
    implementation("com.google.ar:core:1.49.0")

    testImplementation("junit:junit:4.13.2")

    // The android.jar used by unit tests only stubs org.json; this provides a real
    // implementation so the room file format can be tested on the JVM.
    testImplementation("org.json:json:20231013")
}
