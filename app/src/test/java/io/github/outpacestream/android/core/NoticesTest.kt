package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val notices = Notices.all("1.2.3")

    /**
     * Gradle passes the release runtime classpath's `group:name` list. Checking it against the
     * catalog itself, not the source text, means a commented-out entry counts as missing.
     */
    @Test fun everyRuntimeLibraryHasANotice() {
        val property = System.getProperty("outpace.releaseRuntimeModules")
        assertNotNull("run through Gradle, which sets outpace.releaseRuntimeModules", property)
        val modules = property!!.split(',').filter { it.isNotBlank() }
        assertTrue("the release runtime classpath resolved to nothing", modules.isNotEmpty())
        val named = notices.mapNotNull { it.maven }.toSet()
        assertEquals("libraries without a license notice", emptyList<String>(), modules.filterNot { it in named })
    }

    @Test fun namesAreUnique() {
        assertEquals(notices.size, notices.map { it.name }.toSet().size)
    }

    @Test fun everyNoticeLinksItsSource() {
        notices.forEach { assertTrue(it.name, it.url.startsWith("https://")) }
    }

    @Test fun knownVersionsAreShown() {
        val byName = notices.associateBy { it.name }
        assertEquals("1.2.3", byName.getValue("Outpace for Android").version)
        assertEquals(KotlinVersion.CURRENT.toString(), byName.getValue("Kotlin Standard Library").version)
    }

    @Test fun appAndEngineAreAgpl() {
        val byName = notices.associateBy { it.name }
        assertEquals(License.AGPL_3_0, byName.getValue("Outpace for Android").license)
        assertEquals(License.AGPL_3_0, byName.getValue("Outpace engine").license)
    }

    /** The shipped AGPL text is the repository's LICENSE, so the two can't drift apart. */
    @Test fun agplTextMatchesRepositoryLicense() {
        assertEquals(
            File(root, "LICENSE").readText(),
            File(root, "app/src/main/res/raw/license_agpl_3_0.txt").readText(),
        )
    }

    @Test fun everyLicenseHasItsText() {
        License.entries.forEach { license ->
            val file = File(root, "app/src/main/res/raw/license_${license.name.lowercase()}.txt")
            assertTrue(file.path, file.length() > 1_000)
        }
    }
}
