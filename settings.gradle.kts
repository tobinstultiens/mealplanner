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
    }
}

include(":shared")
include(":server")
include(":composeApp")
include(":androidApp")
