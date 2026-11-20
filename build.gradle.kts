import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinToolingSetupTask

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

// A failed test prints its message and stack in the build log, not only in the report. Gradle's default
// prints the exception's class alone: a browser test that failed in CI said nothing but "Error", whatever
// the browser reported (issue 306), and the report is gone with the runner.
allprojects {
    tasks.withType<AbstractTestTask>().configureEach {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            showStackTraces = true
        }
    }
}

// One yarn at a time. The Kotlin plugin runs yarn twice for Wasm: kotlinWasmToolingSetup installs webpack,
// Karma and Mocha (130 MB, 50 to 70 s on a CI runner), kotlinWasmNpmInstall the packages of
// kotlin-js-store/wasm/yarn.lock (a second). Gradle starts both at once, and the plugin makes them take turns
// with yarn's `--mutex network`: the second yarn keeps an idle connection open to the first one's HTTP server.
// Node drops a connection that sends no request within 60 s, and the waiting yarn then exits with 0 without
// installing anything. So whenever the tooling took over a minute and started first, the project's packages
// were missing (`@js-joda/core`, `ws`; issue 306). A build service that lets one of these tasks run at a time
// means no yarn of this build ever waits for another.
abstract class OneYarnAtATime : BuildService<BuildServiceParameters.None>
val oneYarnAtATime = gradle.sharedServices.registerIfAbsent("oneYarnAtATime", OneYarnAtATime::class) {
    maxParallelUsages = 1
}
tasks.withType<KotlinNpmInstallTask>().configureEach { usesService(oneYarnAtATime) }
tasks.withType<KotlinToolingSetupTask>().configureEach { usesService(oneYarnAtATime) }

// The install task does not look at node_modules when it decides it is up to date, and a yarn that quits
// while waiting reports success, so before webpack or a browser test runs, check that every package of
// kotlin-js-store/wasm/yarn.lock is in node_modules: a bad install fails here, and says so, instead of
// minutes later in webpack (issue 239). Another build on the same machine can still hold yarn's mutex too long.
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
                "kotlin-js-store/wasm/yarn.lock lists it. Another yarn on this machine may have held yarn's " +
                "mutex for over a minute (issue 306). Delete build/wasm and run the build again."
        }
    }
}
allprojects {
    tasks.matching {
        it.name.startsWith("wasmJsBrowser") && (it.name.endsWith("Webpack") || it.name.endsWith("Test"))
    }.configureEach {
        dependsOn(rootProject.tasks.named("verifyWasmNpmInstall"))
    }
}
