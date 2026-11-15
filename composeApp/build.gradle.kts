plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    android {
        namespace = "dev.tobins.mealplanner.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest { isIncludeAndroidResources = true }
    }
    // The web client: the same UI compiled to WebAssembly, served by the server.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig { outputFileName = "composeApp.js" }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        // Screenshot tests: Compose rendered by Robolectric on the JVM (see README, "Screenshot tests").
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.roborazzi)
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.activity.compose)
        }
    }
}

// Screenshot tests (src/androidHostTest): `recordScreenshots` rewrites the pictures in
// src/androidHostTest/screenshots; `verifyScreenshots` fails when one differs from its picture and
// leaves the new (`*_actual.png`) and a reference | difference | new picture (`*_compare.png`) in
// build/outputs/roborazzi. The normal `allTests` skips them: Robolectric's rendering is a slow
// extra that the test job doesn't need.
val screenshotTasks = setOf("recordScreenshots", "verifyScreenshots")
val requestedScreenshots = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.filter { it in screenshotTasks }

tasks.withType<Test>().configureEach {
    if (name != "testAndroidHostTest") return@configureEach
    filter { if (requestedScreenshots.isEmpty()) excludeTestsMatching("*ScreenshotTest") else includeTestsMatching("*ScreenshotTest") }
    // The pictures are files Gradle doesn't track, so a run must never be skipped or taken from the cache.
    if (requestedScreenshots.isNotEmpty()) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    systemProperty("roborazzi.test.record", "recordScreenshots" in requestedScreenshots)
    systemProperty("roborazzi.test.verify", "verifyScreenshots" in requestedScreenshots)
}

screenshotTasks.forEach { task ->
    tasks.register(task) {
        group = "verification"
        description = if (task == "recordScreenshots") {
            "Renders the screens and saves the pictures as the new references."
        } else {
            "Renders the screens and fails when one differs from its reference picture."
        }
        dependsOn("testAndroidHostTest")
    }
}
