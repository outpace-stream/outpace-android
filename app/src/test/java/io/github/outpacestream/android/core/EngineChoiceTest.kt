package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineChoiceTest {
    private val remote = RemoteEngine("a1", "TV", Endpoint("192.168.1.5", 6878))

    @Test fun roundTrips() {
        for (c in listOf(EngineChoice.Local("1.0.1"), EngineChoice.Remote("a1"), null)) assertEquals(c, EngineChoices.decode(EngineChoices.encode(c)))
    }

    @Test fun noneEncodesAsNone() = assertEquals("none", EngineChoices.encode(null))

    @Test fun decodeGarbageIsNone() {
        assertNull(EngineChoices.decode("weird"))
        assertNull(EngineChoices.decode("local:"))
        assertNull(EngineChoices.decode(null))
    }

    @Test fun migrateKeepsStoredActive() = assertEquals(EngineChoice.Local("1.0.0"), EngineChoices.migrate("1.0.0", listOf("1.0.1", "1.0.0")))

    @Test fun migrateFallsBackToNewestOnce() = assertEquals(EngineChoice.Local("1.0.1"), EngineChoices.migrate(null, listOf("1.0.1", "1.0.0")))

    @Test fun migrateIgnoresUninstalledActive() = assertEquals(EngineChoice.Local("1.0.1"), EngineChoices.migrate("0.9.0", listOf("1.0.1")))

    @Test fun migrateFreshInstallIsNone() = assertNull(EngineChoices.migrate(null, emptyList()))

    @Test fun resolveKeepsValid() {
        assertEquals(EngineChoice.Local("1.0.1"), EngineChoices.resolve(EngineChoice.Local("1.0.1"), listOf("1.0.1"), emptyList()))
        assertEquals(EngineChoice.Remote("a1"), EngineChoices.resolve(EngineChoice.Remote("a1"), emptyList(), listOf(remote)))
    }

    @Test fun noFallbackToNewest() = assertNull(EngineChoices.resolve(EngineChoice.Local("0.9.0"), listOf("1.0.1"), emptyList()))

    @Test fun resolveMissingRemoteIsNone() = assertNull(EngineChoices.resolve(EngineChoice.Remote("gone"), listOf("1.0.1"), listOf(remote)))

    @Test fun installSelectsOnlyWhenNone() {
        assertEquals(EngineChoice.Local("1.0.2"), EngineChoices.afterInstall(null, "1.0.2"))
        assertEquals(EngineChoice.Remote("a1"), EngineChoices.afterInstall(EngineChoice.Remote("a1"), "1.0.2"))
        assertEquals(EngineChoice.Local("1.0.1"), EngineChoices.afterInstall(EngineChoice.Local("1.0.1"), "1.0.2"))
    }

    @Test fun afterRemoveClearsSelected() {
        assertNull(EngineChoices.afterRemove(EngineChoice.Remote("a1"), EngineChoice.Remote("a1")))
        assertNull(EngineChoices.afterRemove(EngineChoice.Local("1.0.1"), EngineChoice.Local("1.0.1")))
        assertEquals(EngineChoice.Local("1.0.1"), EngineChoices.afterRemove(EngineChoice.Local("1.0.1"), EngineChoice.Remote("a1")))
    }
}
