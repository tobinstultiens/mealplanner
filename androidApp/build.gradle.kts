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

    // AppStartTest starts the real MainActivity under Robolectric, with the app's theme and manifest.
    testOptions {
        unitTests.isIncludeAndroidResources = true
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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

/**
 * Android refuses to install an app whose `<queries>` names more than one action, data scheme, host or
 * type in one `<intent>` ("intent tag may have at most one data scheme"), and neither the build nor lint
 * notices. This checks the merged manifest of every variant before it is assembled or linted.
 */
androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register("verify${name}Queries") {
            group = "verification"
            description = "Checks that the ${variant.name} manifest's <queries> intents can be installed."
            inputs.file(manifest)
            doLast { QueriesCheck.verify(manifest.get().asFile) }
        }
        tasks.matching { it.name == "assemble$name" || it.name == "lint$name" }.configureEach { dependsOn(verify) }

        // The barcode scan (issue 356) reads with a decoder that ships in the app; nothing may pull in Google Play services.
        val runtime = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
        val noPlayServices = tasks.register("verify${name}NoPlayServices") {
            group = "verification"
            description = "Fails when the ${variant.name} app depends on Google Play services (com.google.android.gms)."
            doLast { PlayServicesCheck.verify(runtime.get()) }
        }
        tasks.matching { it.name == "assemble$name" || it.name == "lint$name" }.configureEach { dependsOn(noPlayServices) }
    }
}

/** An object rather than a function of this script, which the configuration cache could not keep. */
object QueriesCheck {
    /** Fails with what Android's package parser would refuse in the `<queries>` of [manifest]. */
    fun verify(manifest: File) {
        val android = "http://schemas.android.com/apk/res/android"
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val queries = document.getElementsByTagName("queries")
        val problems = (0 until queries.length).flatMap { q ->
            val intents = (queries.item(q) as org.w3c.dom.Element).getElementsByTagName("intent")
            (0 until intents.length).flatMap { i ->
                val intent = intents.item(i) as org.w3c.dom.Element
                val actions = intent.getElementsByTagName("action").length
                val data = intent.getElementsByTagName("data").let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } }
                fun count(attribute: String) = data.count { it.hasAttributeNS(android, attribute) }
                listOfNotNull(
                    "intent ${i + 1} has $actions actions, needs exactly one".takeIf { actions != 1 },
                    "intent ${i + 1} names ${count("scheme")} data schemes, at most one".takeIf { count("scheme") > 1 },
                    "intent ${i + 1} names ${count("host")} data hosts, at most one".takeIf { count("host") > 1 },
                    "intent ${i + 1} names ${count("mimeType")} data types, at most one".takeIf { count("mimeType") > 1 },
                )
            }
        }
        check(problems.isEmpty()) { "Android would refuse to install this app; in <queries> of $manifest:\n" + problems.joinToString("\n") }
    }
}

/** An object rather than a function of this script, which the configuration cache could not keep. */
object PlayServicesCheck {
    private const val GROUP = "com.google.android.gms"

    /** Fails naming the libraries of Google Play services in the dependencies below [root], and the first way to each. */
    fun verify(root: org.gradle.api.artifacts.result.ResolvedComponentResult) {
        val found = mutableMapOf<String, String>()
        val seen = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
        fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult, path: String) {
            if (!seen.add(component)) return
            val module = component.moduleVersion
            val here = if (path.isEmpty()) "${module?.name}" else "$path -> ${module?.group}:${module?.name}"
            if (module != null && (module.group == GROUP || module.group.startsWith("$GROUP."))) found.putIfAbsent("${module.group}:${module.name}", here)
            component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { walk(it.selected, here) }
        }
        walk(root, "")
        check(found.isEmpty()) {
            "The app must not depend on Google Play services (issue 356: the barcode scan is read by a decoder inside the app):\n" +
                found.entries.joinToString("\n") { "${it.key}, through ${it.value}" }
        }
    }
}
