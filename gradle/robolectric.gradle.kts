// Robolectric without a download at test time. Applied (from the root build file) to every module
// that has Robolectric among its dependencies: androidHostTest in composeApp, the unit tests of androidApp.
//
// Robolectric fetches the "android-all" jar of the SDK a test runs at from Maven Central when the
// first test starts; a failed fetch fails every Robolectric test of the run (issue 239). Here the jar is
// a normal dependency instead, so Gradle resolves it (with its retries, its cache and a clear
// message), and Robolectric is told to use it offline from a plain directory.
//
// The version is the one Robolectric 4.18 uses for the SDK the tests ask for (`@Config(sdk = [35])`).
// A test at another SDK, or a new Robolectric version, needs the matching entry in
// gradle/libs.versions.toml; Robolectric then fails at once saying which jar is missing in the directory.
val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

val robolectricAndroidAll by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    robolectricAndroidAll(catalog.findLibrary("robolectric-android-all").get())
}

// Robolectric wants the jars side by side in one directory, under their Maven file names.
val robolectricJars = layout.buildDirectory.dir("robolectric-android-all")
val prepareRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricAndroidAll)
    into(robolectricJars)
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricJars)
    val dir = robolectricJars.get().asFile.absolutePath
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", dir)
}
