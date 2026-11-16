plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The address of the owner's server, built into the release. It is not kept in this public repository:
// the release workflow passes it from a repository secret (-PreleaseServerUrl=…). Without it a release
// asks for the address on its login screen, as a debug build does.
val releaseServerUrl = providers.gradleProperty("releaseServerUrl").getOrElse("").trim().trimEnd('/')

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

        // The server a debug build suggests on its login screen, until another is saved. Override per machine
        // with -PserverUrl=… or in ~/.gradle/gradle.properties. The default suits an emulator: 10.0.2.2 is the host machine.
        val serverUrl = providers.gradleProperty("serverUrl").getOrElse("http://10.0.2.2:8080")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("boolean", "SERVER_URL_BUILT_IN", "false")
        // The host whose /app/login opens the app after logging in (an App Link): the release's server.
        // Without one a name that is nobody's server (.invalid is reserved, RFC 2606); the login then comes
        // back through that page's forward to the app's own scheme, as whenever Android does not open the App Link.
        manifestPlaceholders["appLinkHost"] = releaseServerUrl.removePrefix("https://").substringBefore('/').ifEmpty { "mealplanner.invalid" }
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
            // The server a fresh install logs in to, straight away; still changeable in the settings.
            buildConfigField("String", "SERVER_URL", "\"$releaseServerUrl\"")
            buildConfigField("boolean", "SERVER_URL_BUILT_IN", "${releaseServerUrl.isNotEmpty()}")
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
    // The login page in a Custom Tab over the app, instead of a separate browser tab.
    implementation(libs.androidx.browser)
}
