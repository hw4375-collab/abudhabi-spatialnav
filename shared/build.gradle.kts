plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

/**
 * The spatial core: the part of SpatialNav that is reasoning rather than plumbing.
 *
 * Everything here is platform-free Kotlin — navigation geometry, obstacle decisions, the
 * room model — compiled once into both the Android app and the desktop simulator. The
 * platforms contribute only inputs: ARCore poses and depth images on Android, simulated
 * ones on desktop.
 */
kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
    }
    jvm("desktop") {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
    }

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "com.hackson.spatialnav.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
