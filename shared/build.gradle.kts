plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    android {
        namespace = "dev.tobins.mealplanner.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Lets CookingRepoParseTest find a local checkout of the cooking repo.
tasks.withType<Test>().configureEach {
    systemProperty(
        "cookingRepo",
        providers.gradleProperty("cookingRepo").orElse(rootDir.resolve("../cooking").path).get(),
    )
}
