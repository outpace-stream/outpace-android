package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MailboxTest {
    @Test fun deliversToAttachedReceiver() {
        val box = Mailbox<String>()
        val got = ArrayList<String>()
        box.attach { got += it }
        box.post("a")
        assertEquals(listOf("a"), got)
    }

    @Test fun keepsLatestWhileDetachedAndReplaysOnAttach() {
        val box = Mailbox<String>()
        box.post("a")
        box.post("b")
        val got = ArrayList<String>()
        box.attach { got += it }
        assertEquals(listOf("b"), got)
    }

    @Test fun replaysOnlyOnce() {
        val box = Mailbox<String>()
        box.post("a")
        val got = ArrayList<String>()
        val r: (String) -> Unit = { got += it }
        box.attach(r)
        box.detach(r)
        box.attach(r)
        assertEquals(listOf("a"), got)
    }

    @Test fun detachOfStaleReceiverKeepsNewOne() {
        val box = Mailbox<String>()
        val old = ArrayList<String>()
        val new = ArrayList<String>()
        val oldR: (String) -> Unit = { old += it }
        box.attach(oldR)
        box.attach { new += it }
        box.detach(oldR)
        box.post("a")
        assertEquals(emptyList<String>(), old)
        assertEquals(listOf("a"), new)
    }

    @Test fun clearDropsKeptValue() {
        val box = Mailbox<String>()
        box.post("a")
        box.clear()
        val got = ArrayList<String>()
        box.attach { got += it }
        assertEquals(emptyList<String>(), got)
    }
}
