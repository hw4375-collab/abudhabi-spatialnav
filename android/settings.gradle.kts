pluginManagement {
    repositories {
        google()
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}

rootProject.name = "SpatialNav"
include(":app")

// The shared spatial core and the desktop app live beside `android/` rather than inside it,
// so the repository layout says what each directory is. The Gradle build still has its root
// here, which is what keeps "open the android/ directory in Android Studio" true.
include(":shared")
project(":shared").projectDir = file("../shared")
include(":desktop")
project(":desktop").projectDir = file("../desktop")
