plugins {
    id("com.android.application")
}

// App versions are independent of the engine version selected by the user. CI passes
// -PappVersion=X.Y.Z from the release tag; local builds use the placeholder below.
val appVersion = (findProperty("appVersion") as String?) ?: "0.0.0"

fun versionCodeOf(version: String): Int {
    val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val (major, minor, patch) = (parts + listOf(0, 0, 0)).take(3)
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "io.github.outpacestream.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.outpacestream.android"
        minSdk = 21
        targetSdk = 36
        versionName = appVersion
        versionCode = maxOf(1, versionCodeOf(appVersion))
    }

    signingConfigs {
        // Release signing comes from CI secrets (release.yml fails without them); local release builds are unsigned.
        val storeFile = System.getenv("OUTPACE_ANDROID_KEYSTORE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = System.getenv("OUTPACE_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OUTPACE_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("OUTPACE_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Emulator testing only: force the engine targets, e.g. -PengineTargets=aarch64-linux-android.
            val targets = (findProperty("engineTargets") as String?).orEmpty()
            buildConfigField("String", "ENGINE_TARGETS_OVERRIDE", "\"$targets\"")
        }
        release {
            buildConfigField("String", "ENGINE_TARGETS_OVERRIDE", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
        // TranslationsTest reads the resource XML; pass its path rather than rely on the working directory.
        unitTests.all { it.systemProperty("outpace.resDir", file("src/main/res").absolutePath) }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM; tests use the real implementation.
    testImplementation("org.json:json:20250517")
}

// Every library in the release APK needs an entry in Notices, which the licenses screen shows.
// NoticesTest compares the real catalog against this list, so a library without an active
// entry, including one whose entry is commented out, fails the unit tests.
val releaseRuntimeModules = provider { configurations.getByName("releaseRuntimeClasspath") }.flatMap { classpath ->
    classpath.incoming.resolutionResult.rootComponent.map { root ->
        val seen = sortedSetOf<String>()
        fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { dep ->
                val id = dep.selected.id
                if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier && seen.add("${id.group}:${id.module}")) walk(dep.selected)
            }
        }
        walk(root)
        seen.joinToString(",")
    }
}
tasks.withType<Test>().configureEach {
    inputs.property("releaseRuntimeModules", releaseRuntimeModules)
    // NoticesTest reads these straight from disk, so a changed license text must rerun the tests.
    inputs.file(rootProject.layout.projectDirectory.file("LICENSE")).withPropertyName("repositoryLicense")
    inputs.dir(layout.projectDirectory.dir("src/main/res/raw")).withPropertyName("licenseTexts")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Doutpace.releaseRuntimeModules=${releaseRuntimeModules.get()}") })
}
// Release builds run the notice check on their own, so even a bare assembleRelease can't ship a
// library without its notice.
val checkLicenseNotices = tasks.register<Test>("checkLicenseNotices") {
    description = "Runs NoticesTest, which checks every release runtime library has a license notice."
    group = "verification"
    // The file collections, not the task: they depend on compiling the tests, not on running them all.
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("io.github.outpacestream.android.core.NoticesTest")
}
afterEvaluate { tasks.named("preReleaseBuild") { dependsOn(checkLicenseNotices) } }
