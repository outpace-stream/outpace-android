package io.github.outpacestream.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastTest {
    private val id = "0123456789abcdef0123456789abcdef01234567"
    private val e = Endpoint("192.168.1.2", 6878)

    @Test fun parsesTargets() {
        val json = """{"targets":[
            {"id":"3f2a9c0d41b7e865","name":"Living Room TV","manufacturer":"Example Corp","model":"ExampleTV 55","address":"192.168.1.40"},
            {"id":"aa","name":"","manufacturer":null,"model":null,"address":"192.168.1.41"},
            {"name":"no id"}
        ],"age_secs":12}"""
        val t = CastJson.parseTargets(json)
        assertEquals(12, t.ageSecs)
        assertEquals(2, t.targets.size)
        assertEquals(CastTarget("3f2a9c0d41b7e865", "Living Room TV", "Example Corp", "ExampleTV 55", "192.168.1.40"), t.targets[0])
        // A blank name falls back to the id; null fields stay null.
        assertEquals(CastTarget("aa", "aa", null, null, "192.168.1.41"), t.targets[1])
    }

    @Test fun emptyTargetList() = assertEquals(CastTargets(emptyList(), 0), CastJson.parseTargets("""{"targets":[],"age_secs":0}"""))

    @Test fun parsesStatus() {
        val playing = """{"target":"t","transport_state":"PLAYING","transport_status":"OK","speed":"1",
            "cast":{"network":"ace","id":"cid:$id","url":"http://192.168.1.2:6878/cast/s/00.ts","stream_connections":1},"fetching":true}"""
        assertEquals(CastStatus("PLAYING", casting = true, fetching = true), CastJson.parseStatus(playing))
        val none = """{"target":"t","transport_state":"NO_MEDIA_PRESENT","transport_status":"OK","speed":"1","cast":null,"fetching":false}"""
        assertEquals(CastStatus("NO_MEDIA_PRESENT", casting = false, fetching = false), CastJson.parseStatus(none))
    }

    @Test fun mapsErrorCodes() {
        fun f(status: Int, code: String) = CastJson.parseError(status, """{"error":"$code","message":"text"}""").failure
        assertEquals(CastFailure.LoopbackBind, f(409, "loopback_bind"))
        assertEquals(CastFailure.UnsupportedBind, f(409, "unsupported_bind"))
        assertEquals(CastFailure.UnknownTarget, f(404, "unknown_target"))
        assertEquals(CastFailure.StreamNotFound, f(404, "stream_not_found"))
        assertEquals(CastFailure.NoPeers, f(404, "no_peers"))
        assertEquals(CastFailure.StreamUnavailable, f(422, "stream_unavailable"))
        assertEquals(CastFailure.StreamUnavailable, f(422, "origin_hls_unsupported"))
        assertEquals(CastFailure.Capacity, f(503, "capacity_exhausted"))
        assertEquals(CastFailure.Discovery, f(503, "discovery_failed"))
        assertEquals(CastFailure.RendererUnreachable, f(502, "renderer_unreachable"))
        assertEquals(CastFailure.RendererUnreachable, f(502, "renderer_http_error"))
        assertEquals(CastFailure.RendererUnreachable, f(502, "renderer_invalid"))
        assertEquals(CastFailure.RendererTimeout, f(504, "renderer_timeout"))
        assertEquals(CastFailure.Network, f(502, "network"))
        assertEquals(CastFailure.Other, f(400, "invalid_id"))
    }

    @Test fun keepsMessageAndUpnpCode() {
        val e = CastJson.parseError(502, """{"error":"renderer_fault","message":"refused","upnp_error":714}""")
        assertEquals(CastError(CastFailure.RendererFault, "refused", 714), e)
    }

    @Test fun bare404MeansCastingOff() {
        assertEquals(CastFailure.Disabled, CastJson.parseError(404, "").failure)
        assertEquals(CastFailure.Disabled, CastJson.parseError(404, "Not Found").failure)
        assertEquals(CastFailure.Other, CastJson.parseError(500, "").failure)
        assertNull(CastJson.parseError(404, null).message)
    }

    @Test fun playBodyUsesTheSelector() {
        val o = JSONObject(CastJson.playBody(StreamRef.infohash(id)))
        assertEquals("ace", o.getString("network"))
        assertEquals("ih:$id", o.getString("id"))
    }

    @Test fun urls() {
        assertEquals("http://192.168.1.2:6878/cast/targets", StreamUrls.castTargets(e))
        assertEquals("http://192.168.1.2:6878/cast/targets?refresh=1", StreamUrls.castTargets(e, refresh = true))
        assertEquals("http://192.168.1.2:6878/cast/targets/3f2a/play", StreamUrls.castPlay(e, "3f2a"))
        assertEquals("http://192.168.1.2:6878/cast/targets/3f2a/stop", StreamUrls.castStop(e, "3f2a"))
        assertEquals("http://192.168.1.2:6878/cast/targets/a%2Fb/status", StreamUrls.castStatus(e, "a/b"))
    }

    private fun status(state: String?, casting: Boolean = true, fetching: Boolean = true) = CastStatus(state, casting, fetching)

    @Test fun watchPlayingAndPaused() {
        val w = CastWatch(0)
        assertEquals(CastStep(CastMessage.Playing, false), w.status(status("PLAYING"), 1_000))
        assertEquals(CastStep(CastMessage.Paused, false), w.status(status("PAUSED_PLAYBACK", fetching = false), 60_000))
        assertEquals(CastStep(CastMessage.Starting, false), w.status(status("TRANSITIONING", fetching = false), 60_000))
    }

    @Test fun watchNotFetchingAfterGrace() {
        val w = CastWatch(0)
        assertEquals(CastMessage.Starting, w.status(status("PLAYING", fetching = false), 1_000).message)
        assertEquals(CastStep(CastMessage.NotFetching, false), w.status(status("PLAYING", fetching = false), CastWatch.GRACE_MS))
    }

    @Test fun watchTvStoppedEndsAfterGrace() {
        val w = CastWatch(0)
        assertEquals(CastStep(CastMessage.Starting, false), w.status(status("STOPPED", fetching = false), 2_000))
        assertEquals(CastStep(CastMessage.TvStopped, true), w.status(status("STOPPED", fetching = false), CastWatch.GRACE_MS))
        assertEquals(CastStep(CastMessage.TvStopped, true), CastWatch(0).status(status("NO_MEDIA_PRESENT"), 60_000))
    }

    @Test fun watchEndsWhenTheEngineForgotTheCast() {
        assertEquals(CastStep(CastMessage.Ended, true), CastWatch(0).status(status("PLAYING", casting = false), 1_000))
        assertEquals(CastStep(CastMessage.Ended, true), CastWatch(0).failure(CastFailure.UnknownTarget))
        assertEquals(CastStep(CastMessage.Ended, true), CastWatch(0).failure(CastFailure.Disabled))
    }

    @Test fun watchTvNotFoundEndsAfterRepeatedMisses() {
        val w = CastWatch(0)
        repeat(CastWatch.MAX_MISSES - 1) { assertEquals(CastStep(CastMessage.TvNotFound, false), w.failure(CastFailure.RendererUnreachable)) }
        assertEquals(CastStep(CastMessage.TvNotFound, true), w.failure(CastFailure.RendererTimeout))
    }

    @Test fun watchAnswerResetsMisses() {
        val w = CastWatch(0)
        repeat(CastWatch.MAX_MISSES - 1) { w.unreachable() }
        w.status(status("PLAYING"), 60_000)
        assertEquals(CastStep(CastMessage.EngineUnreachable, false), w.unreachable())
    }

    @Test fun playOutcomes() {
        fun after(r: CastPlayResult, tornDown: Boolean = false, stopped: Boolean = false) = CastPlay.after(r, tornDown, stopped)
        assertEquals(CastPlayAction.Publish, after(CastPlayResult.Accepted))
        // Stop pressed after the play was queued: tracked, so a stop without answer stays retryable.
        assertEquals(CastPlayAction.StopTracked, after(CastPlayResult.Accepted, stopped = true))
        // Unknown outcome: the TV may play; tracked and stopped.
        assertEquals(CastPlayAction.StopTracked, after(CastPlayResult.Unknown))
        assertEquals(CastPlayAction.StopTracked, after(CastPlayResult.Unknown, stopped = true))
        // The engine session ended: nothing can track it, one bounded stop.
        assertEquals(CastPlayAction.StopUntracked, after(CastPlayResult.Accepted, tornDown = true))
        assertEquals(CastPlayAction.StopUntracked, after(CastPlayResult.Unknown, tornDown = true, stopped = true))
        // Refused before reaching the TV, or never sent: nothing to undo.
        assertEquals(CastPlayAction.Nothing, after(CastPlayResult.Refused))
        assertEquals(CastPlayAction.Nothing, after(CastPlayResult.Refused, tornDown = true, stopped = true))
    }

    @Test fun playErrorsAfterReachingTheTvAreUnknown() {
        fun result(status: Int, code: String) = CastPlay.resultOf(CastJson.parseError(status, """{"error":"$code","message":"m"}""").failure)
        for ((status, code) in listOf(504 to "renderer_timeout", 502 to "renderer_unreachable", 502 to "renderer_http_error", 502 to "renderer_invalid", 502 to "renderer_fault")) {
            assertEquals(code, CastPlayResult.Unknown, result(status, code))
        }
        for ((status, code) in listOf(
            404 to "unknown_target", 409 to "loopback_bind", 409 to "unsupported_bind", 503 to "capacity_exhausted",
            400 to "invalid_request", 400 to "invalid_id", 404 to "stream_not_found", 404 to "no_peers",
            422 to "stream_unavailable", 422 to "origin_hls_unsupported", 502 to "network",
        )) {
            assertEquals(code, CastPlayResult.Refused, result(status, code))
        }
        // Casting off: a bare 404 before anything else.
        assertEquals(CastPlayResult.Refused, CastPlay.resultOf(CastJson.parseError(404, "").failure))
    }

    @Test fun gateForThisDevice() {
        val on = EngineSettings(cast = true, lan = true)
        assertEquals(CastBlock.Old("1.1.0"), CastGate.check("1.1.0", on, null))
        assertEquals(CastBlock.Off, CastGate.check("1.2.0", EngineSettings(lan = true), null))
        assertEquals(CastBlock.NeedsLan, CastGate.check("1.2.0", EngineSettings(cast = true), null))
        assertEquals(CastBlock.NeedsRestart, CastGate.check("1.2.0", on, EngineSettings(lan = true)))
        assertEquals(CastBlock.NeedsRestart, CastGate.check("1.2.0", on, EngineSettings(cast = true)))
        assertNull(CastGate.check("1.2.0", on, on))
        assertNull(CastGate.check("1.3.0", on, null))
        assertFalse(CastGate.supports("1.2.0-rc.1"))
        assertTrue(CastGate.supports("1.2.0"))
    }
}
