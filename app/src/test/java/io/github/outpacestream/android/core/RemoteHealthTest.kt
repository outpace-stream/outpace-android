package io.github.outpacestream.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHealthTest {
    @Test fun okWithNewline() = assertTrue(RemoteHealth.isOk("ok\n"))

    @Test fun htmlIsNotOk() = assertFalse(RemoteHealth.isOk("<html>ok</html>"))

    @Test fun emptyIsNotOk() = assertFalse(RemoteHealth.isOk(""))
}
