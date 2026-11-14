package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionJsonTest {
    @Test fun readsVersion() =
        assertEquals("1.0.2", VersionJson.parse("""{"result":{"version":"1.0.2","code":10002,"platform":"linux"},"error":null}"""))

    @Test fun errorGivesNull() = assertNull(VersionJson.parse("""{"result":null,"error":"unknown method"}"""))

    @Test fun blankVersionGivesNull() = assertNull(VersionJson.parse("""{"result":{"version":" "},"error":null}"""))

    @Test fun garbageGivesNull() = assertNull(VersionJson.parse("<html>"))
}
