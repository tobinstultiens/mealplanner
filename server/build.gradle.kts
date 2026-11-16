plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
}

sqldelight {
    databases {
        create("Database") {
            packageName = "dev.tobins.mealplanner.server.db"
        }
    }
}

application {
    mainClass = "dev.tobins.mealplanner.server.ApplicationKt"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.json)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.jgit)
    implementation(libs.anthropic.java)
    implementation(libs.kaml)
    implementation(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
}

// What the server says it runs (BuildInfo, GET /health): the commit (GITHUB_SHA in CI, else "dev") and the
// latest v* tag reachable from it without its "v" (else "dev"; CI must fetch the tags for it).
val buildInfo = tasks.register<WriteProperties>("buildInfo") {
    val commit = providers.environmentVariable("GITHUB_SHA").orElse("dev")
    val version = providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v*", "--abbrev=0")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().removePrefix("v").ifEmpty { "dev" } }
    destinationFile = layout.buildDirectory.file("generated/buildinfo/build-info.properties")
    property("commit", commit)
    property("version", version)
}

sourceSets.main {
    resources.srcDir(buildInfo.map { it.destinationFile.get().asFile.parentFile })
}

// The web client (composeApp's WebAssembly build) ships inside the server jar and is served at "/".
tasks.named<Jar>("shadowJar") {
    dependsOn(":composeApp:wasmJsBrowserDistribution")
    from(rootDir.resolve("composeApp/build/dist/wasmJs/productionExecutable")) {
        into("web")
    }
}
