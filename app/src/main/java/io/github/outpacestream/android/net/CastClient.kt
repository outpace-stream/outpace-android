package io.github.outpacestream.android.net

import io.github.outpacestream.android.core.CastError
import io.github.outpacestream.android.core.CastJson
import io.github.outpacestream.android.core.CastStatus
import io.github.outpacestream.android.core.CastTargets
import io.github.outpacestream.android.core.Endpoint
import io.github.outpacestream.android.core.StreamRef
import io.github.outpacestream.android.core.StreamUrls
import java.io.IOException

/** An engine's answer to a cast request it refused; see [CastJson.parseError]. */
class CastException(val error: CastError) : IOException(error.message ?: error.failure.name)

/**
 * The engine's `/cast/...` API (engine 1.2.0 and later), on this device or a remote engine. Blocking,
 * each call with a hard total deadline; call from a background thread only. Throws
 * [CastException] for an error answer and [IOException] when the engine does not answer.
 */
object CastClient {
    /** Discovery waits about 2 s, then reads each device description with a 2 s timeout. */
    private const val TARGETS_MS = 12_000

    /** `play` starts the stream, then gives the TV 15 s per attempt, with one retry as `video/mpeg`. */
    private const val PLAY_MS = 45_000

    /** The engine gives a renderer 5 s for `stop` and `status`. */
    private const val CONTROL_MS = 8_000

    fun targets(e: Endpoint, refresh: Boolean): CastTargets =
        CastJson.parseTargets(call("GET", StreamUrls.castTargets(e, refresh), TARGETS_MS))

    fun play(e: Endpoint, target: String, ref: StreamRef) {
        call("POST", StreamUrls.castPlay(e, target), PLAY_MS, CastJson.playBody(ref))
    }

    fun stop(e: Endpoint, target: String) {
        call("POST", StreamUrls.castStop(e, target), CONTROL_MS)
    }

    fun status(e: Endpoint, target: String): CastStatus =
        CastJson.parseStatus(call("GET", StreamUrls.castStatus(e, target), CONTROL_MS))

    private fun call(method: String, url: String, totalMs: Int, json: String? = null): String {
        val r = Http.requestWithin(method, url, totalMs, json)
        if (r.code !in 200..299) throw CastException(CastJson.parseError(r.code, r.body))
        return r.body
    }
}
