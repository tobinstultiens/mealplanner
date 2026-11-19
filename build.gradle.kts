plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Every module with Robolectric among its dependencies gets its android-all jar from Gradle and runs
// Robolectric offline (gradle/robolectric.gradle.kts), so no test downloads anything.
subprojects {
    afterEvaluate {
        if (configurations.any { c -> c.allDependencies.any { it.group == "org.robolectric" } }) {
            apply(from = rootProject.file("gradle/robolectric.gradle.kts"))
        }
    }
}

// The Wasm npm install has once ended without a package webpack needed (`Can't resolve '@js-joda/core'`,
// issue 239), and the build only failed minutes later in webpack. Before webpack runs, check that every
// package of kotlin-js-store/wasm/yarn.lock is in node_modules, so a bad install fails here, and says so.
// The install task does not look at node_modules when it decides it is up to date, hence a check of its own.
val verifyWasmNpmInstall by tasks.registering {
    dependsOn("kotlinWasmNpmInstall")
    // Plain values: a task action may not refer to the build script (configuration cache).
    val yarnLock = layout.projectDirectory.file("kotlin-js-store/wasm/yarn.lock").asFile
    val nodeModules = layout.buildDirectory.dir("wasm/node_modules").get().asFile
    doLast {
        // A lock entry starts at column 0: `name@range:` or `"@scope/name@range", name@range:`.
        val locked = yarnLock.readLines()
            .filter { it.isNotEmpty() && !it.startsWith(" ") && !it.startsWith("#") }
            .flatMap { it.removeSuffix(":").split(", ") }
            .map { it.trim('"').substringBeforeLast('@') }
            .distinct()
        val missing = locked.filterNot { File(nodeModules, "$it/package.json").isFile }
        check(missing.isEmpty()) {
            "The npm install for Wasm left out ${missing.joinToString()} in $nodeModules although " +
                "kotlin-js-store/wasm/yarn.lock lists it. Delete build/wasm and run the build again."
        }
    }
}
allprojects {
    tasks.matching { it.name.startsWith("wasmJsBrowser") && it.name.endsWith("Webpack") }.configureEach {
        dependsOn(rootProject.tasks.named("verifyWasmNpmInstall"))
    }
}
