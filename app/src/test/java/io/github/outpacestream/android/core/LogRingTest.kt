package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LogRingTest {
    @Test
    fun dropsOldestLinesOnceFull() {
        val ring = LogRing(2)
        ring.add("a")
        ring.add("b")
        ring.add("c")
        assertEquals(listOf("b", "c"), ring.snapshot())
    }

    @Test
    fun truncatesLongLines() {
        val ring = LogRing(1)
        ring.add("x".repeat(LogRing.MAX_LINE + 10))
        assertEquals(LogRing.MAX_LINE, ring.snapshot().single().length)
    }

    @Test
    fun revisionChangesWhenAFullRingKeepsItsSizeAndLastLine() {
        val ring = LogRing(2)
        ring.add("ready")
        ring.add("ready")
        val shown = ring.revision()
        ring.add("error")
        ring.add("ready")
        assertEquals(listOf("error", "ready"), ring.snapshot())
        assertNotEquals(shown, ring.revision())
    }

    @Test
    fun clearAdvancesTheRevision() {
        val ring = LogRing(2)
        val before = ring.revision()
        ring.clear()
        assertNotEquals(before, ring.revision())
    }
}
