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

// Every library in the release APK needs an entry in Notices.ALL, which the licenses screen shows.
// This fails the build when a runtime dependency lands without one.
val checkLicenseNotices = tasks.register("checkLicenseNotices") {
    description = "Checks that Notices.kt names every library on the release runtime classpath."
    val notices = layout.projectDirectory.file("src/main/java/io/github/outpacestream/android/core/Notices.kt")
    val modules = configurations.named("releaseRuntimeClasspath").flatMap { classpath ->
        classpath.incoming.resolutionResult.rootComponent.map { root ->
            val seen = sortedSetOf<String>()
            fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { dep ->
                    val id = dep.selected.id
                    if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier && seen.add("${id.group}:${id.module}")) walk(dep.selected)
                }
            }
            walk(root)
            seen.toList()
        }
    }
    inputs.file(notices)
    inputs.property("modules", modules)
    doLast {
        val text = notices.asFile.readText()
        val missing = modules.get().filterNot { text.contains("maven = \"$it\"") }
        if (missing.isNotEmpty()) {
            throw GradleException("No license notice in Notices.kt for: ${missing.joinToString()}")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkLicenseNotices) }
