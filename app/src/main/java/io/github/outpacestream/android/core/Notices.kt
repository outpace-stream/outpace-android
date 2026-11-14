package io.github.outpacestream.android.core

/** A license whose full text ships in the APK as a raw resource. */
enum class License(val spdx: String, val title: String) {
    AGPL_3_0("AGPL-3.0", "GNU Affero General Public License v3.0"),
    APACHE_2_0("Apache-2.0", "Apache License 2.0"),
}

/**
 * One component of the app, or one it runs, and the license it comes under. [version] is null
 * when it isn't known at run time. [maven] is the `group:name` of a runtime library; NoticesTest
 * fails when the release runtime classpath holds a library that no notice names.
 */
data class Notice(
    val name: String,
    val version: String?,
    val copyright: String,
    val license: License,
    val url: String,
    val detail: String,
    val maven: String? = null,
)

object Notices {
    /** Every notice, in display order. [appVersion] is this build's version name. */
    fun all(appVersion: String): List<Notice> = listOf(
        Notice(
            name = "Outpace for Android",
            version = appVersion,
            copyright = "Copyright Outpace contributors",
            license = License.AGPL_3_0,
            url = "https://github.com/outpace-stream/outpace-android",
            detail = "This app.",
        ),
        Notice(
            name = "Outpace engine",
            version = null,
            copyright = "Copyright Outpace contributors",
            license = License.AGPL_3_0,
            url = "https://github.com/outpace-stream/outpace-engine",
            detail = "Not bundled: the app downloads the release you choose and runs it unmodified. " +
                "Each release archive carries its own license.",
        ),
        Notice(
            name = "Kotlin Standard Library",
            version = KotlinVersion.CURRENT.toString(),
            copyright = "Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors",
            license = License.APACHE_2_0,
            url = "https://github.com/JetBrains/kotlin",
            detail = "Runtime library.",
            maven = "org.jetbrains.kotlin:kotlin-stdlib",
        ),
        Notice(
            name = "JetBrains Java Annotations",
            version = null,
            copyright = "Copyright 2000-2013 JetBrains s.r.o.",
            license = License.APACHE_2_0,
            url = "https://github.com/JetBrains/java-annotations",
            detail = "Used by the Kotlin Standard Library.",
            maven = "org.jetbrains:annotations",
        ),
        Notice(
            name = "Material Design icons",
            version = null,
            copyright = "Copyright Google LLC",
            license = License.APACHE_2_0,
            url = "https://github.com/google/material-design-icons",
            detail = "The home, search and settings icons.",
        ),
    )
}
