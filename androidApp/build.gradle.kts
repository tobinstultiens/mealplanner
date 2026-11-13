plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.tobins.mealplanner.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.tobins.mealplanner"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // The release workflow passes these from the tag (v1.2.3 -> 1.2.3 and 10203).
        versionCode = providers.gradleProperty("appVersionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("appVersionName").getOrElse("0.1.0")

        // Server to talk to. Override per machine with -PserverUrl=… -PapiToken=… or in
        // ~/.gradle/gradle.properties. The default suits an emulator: 10.0.2.2 is the host machine.
        val serverUrl = providers.gradleProperty("serverUrl").getOrElse("http://10.0.2.2:8080")
        val apiToken = providers.gradleProperty("apiToken").getOrElse("dev-token")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "API_TOKEN", "\"$apiToken\"")
    }

    // Release signing reads its key from Gradle properties kept outside the repo, e.g. in
    // ~/.gradle/gradle.properties: mealplanner.keystore, mealplanner.keystorePassword,
    // mealplanner.keyAlias, mealplanner.keyPassword. Without them the release APK is unsigned.
    val keystore = providers.gradleProperty("mealplanner.keystore").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.gradleProperty("mealplanner.keystorePassword").get()
                keyAlias = providers.gradleProperty("mealplanner.keyAlias").get()
                keyPassword = providers.gradleProperty("mealplanner.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            // No token is built into a release: the app asks for it on first launch.
            val releaseUrl = providers.gradleProperty("releaseServerUrl").getOrElse("https://mealplanner.tstultiens.com")
            buildConfigField("String", "SERVER_URL", "\"$releaseUrl\"")
            buildConfigField("String", "API_TOKEN", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
}
