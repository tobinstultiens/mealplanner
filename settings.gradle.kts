rootProject.name = "mealplanner"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // No repositoriesMode: the Kotlin web targets add their own download sources for Node and Binaryen,
    // which FAIL_ON_PROJECT_REPOS rejects and PREFER_SETTINGS ignores.
    repositories {
        google()
        mavenCentral()
        // Maven Central now and then answers 404 for an artifact that exists (the android-all jar of
        // Robolectric, issue 239) and Gradle does not retry a 404. Google's mirror of Central is the
        // second place to look, for Robolectric only.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeGroup("org.robolectric") }
        }
    }
}

include(":shared")
include(":server")
include(":composeApp")
include(":androidApp")
