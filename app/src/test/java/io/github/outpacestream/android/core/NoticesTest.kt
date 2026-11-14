package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    @Test fun namesAreUnique() {
        assertEquals(Notices.ALL.size, Notices.ALL.map { it.name }.toSet().size)
    }

    @Test fun everyNoticeLinksItsSource() {
        Notices.ALL.forEach { assertTrue(it.name, it.url.startsWith("https://")) }
    }

    @Test fun appAndEngineAreAgpl() {
        val byName = Notices.ALL.associateBy { it.name }
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
