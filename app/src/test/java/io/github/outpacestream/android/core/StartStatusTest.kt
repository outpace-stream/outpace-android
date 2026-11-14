package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartStatusTest {
    private val id = "cid:0123456789abcdef0123456789abcdef01234567"

    private fun modern(phase: String, peers: Int = 0, error: String = "null") =
        StartStatusJson.parse("""{"network":"ace","id":"$id","phase":"$phase","error":$error,"clients":0,"peers":$peers}""")

    private fun legacy(peers: Int, bufferMs: Long = 0) =
        StartStatusJson.parse("""{"network":"ace","id":"$id","clients":1,"peers":$peers,"bitrate":0,"buffer_ms":$bufferMs}""")

    @Test fun parsesPhaseAndPeers() {
        val s = modern("connecting", peers = 3)
        assertEquals(StartPhase.Connecting, s.phase)
        assertEquals(3, s.peers)
        assertTrue(s.hasPhase)
        assertNull(s.errorCode)
    }

    @Test fun parsesFailure() {
        val s = modern("failed", error = """{"code":"no_peers","message":"no peer is sharing this stream"}""")
        assertEquals(StartPhase.Failed, s.phase)
        assertEquals("no_peers", s.errorCode)
        assertEquals("no peer is sharing this stream", s.errorMessage)
    }

    @Test fun legacyStatusHasNoPhase() {
        val s = legacy(peers = 0)
        assertNull(s.phase)
        assertFalse(s.hasPhase)
        assertEquals(0L, s.bufferMs)
    }

    @Test fun unknownPhaseIsKeptAsPresent() {
        val s = modern("warming")
        assertNull(s.phase)
        assertTrue(s.hasPhase)
    }

    @Test fun mapsEveryErrorCode() {
        assertEquals(StartMessage.NoPeers, StartMessage.forCode("no_peers"))
        assertEquals(StartMessage.Network, StartMessage.forCode("network"))
        assertEquals(StartMessage.NotAStream, StartMessage.forCode("not_found"))
        assertEquals(StartMessage.Unresolvable, StartMessage.forCode("unresolvable"))
        assertEquals(StartMessage.Protected, StartMessage.forCode("protected"))
        assertEquals(StartMessage.Capacity, StartMessage.forCode("capacity"))
        assertEquals(StartMessage.NoSpace, StartMessage.forCode("no_space"))
        assertEquals(StartMessage.Failed, StartMessage.forCode("internal"))
        assertEquals(StartMessage.Failed, StartMessage.forCode("something_new"))
        assertEquals(StartMessage.Failed, StartMessage.forCode(null))
    }

    @Test fun phasesStepThroughToPlaying() {
        val w = StartWatch(0)
        assertEquals(StartStep(StartView(StartMessage.Waiting), false), w.notFound(500))
        assertEquals(StartStep(StartView(StartMessage.Resolving), false), w.status(modern("resolving"), 1_000))
        assertEquals(StartStep(StartView(StartMessage.Connecting, 2), false), w.status(modern("connecting", 2), 2_000))
        assertEquals(StartStep(StartView(StartMessage.Buffering, 4), false), w.status(modern("buffering", 4), 3_000))
        assertEquals(StartStep(null, true), w.status(modern("playing", 5), 4_000))
    }

    @Test fun failureStopsWithTheCodeMessage() {
        val step = StartWatch(0).status(modern("failed", error = """{"code":"network","message":"x"}"""), 1_000)
        assertTrue(step.done)
        assertEquals(StartView(StartMessage.Network), step.view)
    }

    @Test fun unresolvableKeepsTheEngineDetail() {
        val step = StartWatch(0).status(modern("failed", error = """{"code":"unresolvable","message":"local_descriptor_is_not_live"}"""), 1_000)
        assertEquals(StartView(StartMessage.Unresolvable, detail = "local_descriptor_is_not_live"), step.view)
    }

    @Test fun legacyNoPeersFor20SecondsHintsOffline() {
        val w = StartWatch(0)
        assertEquals(StartView(StartMessage.Connecting, 0), w.status(legacy(0), 2_000).view)
        assertEquals(StartView(StartMessage.Connecting, 0), w.status(legacy(0), 21_000).view)
        val hint = w.status(legacy(0), 22_000)
        assertEquals(StartView(StartMessage.NoPeersYet), hint.view)
        assertFalse(hint.done)
    }

    @Test fun legacyPeersResetTheOfflineClock() {
        val w = StartWatch(0)
        w.status(legacy(0), 1_000)
        assertEquals(StartView(StartMessage.Connecting, 2), w.status(legacy(2), 15_000).view)
        assertEquals(StartView(StartMessage.Connecting, 0), w.status(legacy(0), 16_000).view)
        assertEquals(StartView(StartMessage.Connecting, 0), w.status(legacy(0), 30_000).view)
        assertEquals(StartView(StartMessage.NoPeersYet), w.status(legacy(0), 36_000).view)
    }

    @Test fun legacyBufferedWithPeersStops() {
        assertEquals(StartStep(null, true), StartWatch(0).status(legacy(3, bufferMs = 1_500), 5_000))
    }

    @Test fun neverSeenStopsAfterNoSessionWindow() {
        val w = StartWatch(0)
        assertFalse(w.notFound(29_000).done)
        assertEquals(StartStep(null, true), w.notFound(30_000))
    }

    @Test fun sessionGoneStops() {
        val w = StartWatch(0)
        w.status(modern("connecting"), 1_000)
        assertEquals(StartStep(null, true), w.notFound(2_000))
    }

    @Test fun timeoutStopsAndKeepsOnlyTheOfflineHint() {
        val modernWatch = StartWatch(0)
        assertEquals(StartStep(null, true), modernWatch.status(modern("connecting"), StartWatch.WATCH_MS))
        val legacyWatch = StartWatch(0)
        legacyWatch.status(legacy(0), 1_000)
        assertEquals(StartStep(StartView(StartMessage.NoPeersYet), true), legacyWatch.status(legacy(0), StartWatch.WATCH_MS))
    }

    @Test fun unreachableKeepsTheLastView() {
        val w = StartWatch(0)
        w.status(modern("connecting", 1), 1_000)
        assertEquals(StartStep(StartView(StartMessage.Connecting, 1), false), w.unreachable(2_000))
        assertTrue(w.unreachable(StartWatch.WATCH_MS).done)
    }

    @Test fun unknownPhaseKeepsTheLastView() {
        val w = StartWatch(0)
        w.status(modern("resolving"), 1_000)
        assertEquals(StartView(StartMessage.Resolving), w.status(modern("warming"), 2_000).view)
    }
}
