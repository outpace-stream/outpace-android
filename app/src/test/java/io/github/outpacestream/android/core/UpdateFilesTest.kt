package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UpdateFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val helloSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun dirWith(vararg names: String): File {
        val dir = tmp.newFolder("app-update")
        names.forEach { File(dir, it).writeText("x") }
        return dir
    }

    @Test fun prepareDirRemovesEverything() {
        val dir = dirWith("outpace-android-0.2.0.apk.part", "x")
        UpdateFiles.prepareDir(dir)
        assertTrue(dir.isDirectory)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun prepareDirCreatesMissingDir() {
        val dir = File(tmp.root, "missing/app-update")
        UpdateFiles.prepareDir(dir)
        assertTrue(dir.isDirectory)
    }

    @Test fun staleApkDeletedWhenInstalledCaughtUp() {
        val dir = dirWith("outpace-android-0.2.0.apk", "outpace-android-0.3.0.apk", "a.part")
        UpdateFiles.deleteStale(dir, "0.2.0")
        assertEquals(listOf("outpace-android-0.3.0.apk"), dir.list()!!.toList())
    }

    @Test fun deleteStaleToleratesMissingDir() = UpdateFiles.deleteStale(File(tmp.root, "none"), "0.2.0")

    @Test fun verifyAcceptsMatchingChecksum() {
        val f = tmp.newFile("outpace-android-0.2.0.apk").apply { writeText("hello") }
        UpdateFiles.verify(f, "$helloSha  outpace-android-0.2.0.apk\n", "outpace-android-0.2.0.apk")
    }

    @Test fun verifyRejectsMismatch() {
        val f = tmp.newFile("outpace-android-0.2.0.apk").apply { writeText("hello") }
        val e = assertThrows(IOException::class.java) {
            UpdateFiles.verify(f, "${"0".repeat(64)}  outpace-android-0.2.0.apk", "outpace-android-0.2.0.apk")
        }
        assertEquals("checksum mismatch", e.message)
    }

    @Test fun verifyRejectsWrongName() {
        val f = tmp.newFile("outpace-android-0.2.0.apk").apply { writeText("hello") }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateFiles.verify(f, "$helloSha  other.apk", "outpace-android-0.2.0.apk")
        }
    }
}
