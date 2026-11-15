package io.github.outpacestream.android.core

import org.json.JSONObject

/** A DLNA renderer from the engine's `GET /cast/targets`. */
data class CastTarget(
    val id: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val address: String,
)

/** One `GET /cast/targets` answer; [ageSecs] is how old the engine's cached search is. */
data class CastTargets(val targets: List<CastTarget>, val ageSecs: Long)

/**
 * One `GET /cast/targets/{target}/status` answer. [casting] is false when the engine has no cast
 * for this renderer (the `cast` field is `null`); [fetching] is whether the TV still reads the stream.
 */
data class CastStatus(val transportState: String?, val casting: Boolean, val fetching: Boolean)

/** Why a cast request failed, from the engine's `{"error":"<code>","message":...}` and HTTP status. */
enum class CastFailure {
    /** `404` with no error code: the `/cast/...` routes are off (or the engine predates them). */
    Disabled,
    UnknownTarget,
    LoopbackBind,
    UnsupportedBind,
    StreamNotFound,
    NoPeers,
    StreamUnavailable,
    Capacity,
    RendererFault,
    RendererUnreachable,
    RendererTimeout,
    Discovery,
    Network,
    Other,
    ;

    /** Failures that say the TV itself is gone or not answering. */
    val tvMissing: Boolean get() = this == RendererUnreachable || this == RendererTimeout

    companion object {
        fun fromCode(status: Int, code: String?): CastFailure = when (code) {
            null, "" -> if (status == 404) Disabled else Other
            "unknown_target" -> UnknownTarget
            "loopback_bind" -> LoopbackBind
            "unsupported_bind" -> UnsupportedBind
            "stream_not_found" -> StreamNotFound
            "no_peers" -> NoPeers
            "stream_unavailable", "origin_hls_unsupported" -> StreamUnavailable
            "capacity_exhausted" -> Capacity
            "renderer_fault" -> RendererFault
            "renderer_unreachable", "renderer_http_error", "renderer_invalid" -> RendererUnreachable
            "renderer_timeout" -> RendererTimeout
            "discovery_failed" -> Discovery
            "network" -> Network
            else -> Other
        }
    }
}

/** A failed cast request: the mapped [failure], plus the engine's message and UPnP error code when given. */
data class CastError(val failure: CastFailure, val message: String?, val upnpError: Int? = null)

object CastJson {
    fun parseTargets(json: String): CastTargets {
        val o = JSONObject(json)
        val array = o.optJSONArray("targets")
        val targets = ArrayList<CastTarget>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val t = array.optJSONObject(i) ?: continue
                val id = t.text("id") ?: continue
                targets += CastTarget(
                    id = id,
                    name = t.text("name") ?: id,
                    manufacturer = t.text("manufacturer"),
                    model = t.text("model"),
                    address = t.text("address").orEmpty(),
                )
            }
        }
        return CastTargets(targets, o.optLong("age_secs", 0))
    }

    fun parseStatus(json: String): CastStatus {
        val o = JSONObject(json)
        return CastStatus(
            transportState = o.text("transport_state"),
            casting = o.has("cast") && !o.isNull("cast"),
            fetching = o.optBoolean("fetching", false),
        )
    }

    /** Reads an error answer; [body] may be empty or not JSON (a bare `404` when casting is off). */
    fun parseError(status: Int, body: String?): CastError {
        val o = try { body?.takeIf { it.isNotBlank() }?.let(::JSONObject) } catch (_: Exception) { null }
        val code = o?.text("error")
        val upnp = o?.takeIf { it.has("upnp_error") && !it.isNull("upnp_error") }?.optInt("upnp_error")
        return CastError(CastFailure.fromCode(status, code), o?.text("message"), upnp)
    }

    /** The `play` request body. The engine accepts the prefixed selector, so `ih:` works too. */
    fun playBody(ref: StreamRef): String = JSONObject().put("network", "ace").put("id", ref.selector).toString()

    private fun JSONObject.text(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null
}

/** What a cast's status poll means for the user. */
enum class CastMessage {
    /** Just sent, or the TV reports `TRANSITIONING`. */
    Starting,
    Playing,
    Paused,

    /** The TV says it plays, but no longer reads the stream. */
    NotFetching,

    /** The TV stopped playback (`STOPPED` or `NO_MEDIA_PRESENT`). */
    TvStopped,

    /** The TV does not answer the engine. */
    TvNotFound,

    /** The engine no longer has this cast (it restarted, or something else stopped it). */
    Ended,

    /** The engine itself did not answer the status request. */
    EngineUnreachable,

    /** A Stop did not reach the engine, so the TV may still play; Stop can be tried again. */
    StopFailed,
}

/** The result of one status poll: what to show, and whether the cast is over. */
data class CastStep(val message: CastMessage, val ended: Boolean)

/**
 * Decides what each status poll of an active cast means. Pure, so the service's poll stays a thin
 * loop. A TV that is briefly silent or still settling right after `play` is not treated as gone:
 * stopped states count only after [GRACE_MS], and missing answers only after [MAX_MISSES] in a row.
 */
class CastWatch(private val startedAt: Long) {
    private var misses = 0

    fun status(s: CastStatus, now: Long): CastStep {
        misses = 0
        if (!s.casting) return CastStep(CastMessage.Ended, ended = true)
        val settling = now - startedAt < GRACE_MS
        return when (s.transportState?.uppercase()) {
            "PLAYING" -> when {
                s.fetching -> CastStep(CastMessage.Playing, false)
                settling -> CastStep(CastMessage.Starting, false)
                else -> CastStep(CastMessage.NotFetching, false)
            }
            "PAUSED_PLAYBACK" -> CastStep(CastMessage.Paused, false)
            "STOPPED", "NO_MEDIA_PRESENT" ->
                if (settling) CastStep(CastMessage.Starting, false) else CastStep(CastMessage.TvStopped, true)
            else -> CastStep(if (s.fetching) CastMessage.Playing else CastMessage.Starting, false)
        }
    }

    /** A status request the engine answered with an error. */
    fun failure(f: CastFailure): CastStep = when (f) {
        // The engine forgot the renderer, or casting was turned off: nothing left to follow.
        CastFailure.UnknownTarget -> CastStep(CastMessage.Ended, true)
        CastFailure.Disabled -> CastStep(CastMessage.Ended, true)
        else -> miss(if (f.tvMissing) CastMessage.TvNotFound else CastMessage.EngineUnreachable)
    }

    /** A status request that got no answer from the engine. */
    fun unreachable(): CastStep = miss(CastMessage.EngineUnreachable)

    private fun miss(message: CastMessage): CastStep {
        misses++
        return CastStep(message, ended = misses >= MAX_MISSES)
    }

    companion object {
        /** After `play`, how long a renderer may report stopped or not fetching while it opens the stream. */
        const val GRACE_MS = 15_000L
        const val MAX_MISSES = 3

        /** Status polls while the cast screen shows, and from the service otherwise. */
        const val VISIBLE_POLL_MS = 3_000L
        const val BACKGROUND_POLL_MS = 30_000L
    }
}

/** How a `play` request ended: answered with success, answered with an error, or no answer at all. */
enum class CastPlayResult { Accepted, Refused, Unknown }

/** What to do after a `play`: track the new cast, stop the TV again, or nothing. */
enum class CastPlayAction { Publish, Stop, Nothing }

object CastPlay {
    /**
     * Decides what follows a `play`. [superseded] is true when the engine session ended or the user
     * pressed Stop while the request was in flight. A refused request started nothing. Without an
     * answer the engine may still have started the TV, so it gets one stop, as does an accepted
     * cast nobody wants any more; the app never leaves a TV playing that it does not track.
     */
    fun after(result: CastPlayResult, superseded: Boolean): CastPlayAction = when (result) {
        CastPlayResult.Refused -> CastPlayAction.Nothing
        CastPlayResult.Unknown -> CastPlayAction.Stop
        CastPlayResult.Accepted -> if (superseded) CastPlayAction.Stop else CastPlayAction.Publish
    }
}

/** What stops this device's engine from casting, decided from local facts before any request. */
sealed interface CastBlock {
    data class Old(val version: String) : CastBlock
    data object Off : CastBlock
    data object NeedsLan : CastBlock
    data object NeedsRestart : CastBlock
}

object CastGate {
    /**
     * For this device's engine: [version] is the running or selected version, [saved] the stored
     * settings and [running] the settings the running engine started with (null when stopped).
     */
    fun check(version: String, saved: EngineSettings, running: EngineSettings?): CastBlock? = when {
        Releases.compareVersions(version, EngineSettings.CAST_MIN_VERSION) < 0 -> CastBlock.Old(version)
        !saved.cast -> CastBlock.Off
        !saved.lan -> CastBlock.NeedsLan
        running != null && (!running.cast || !running.lan) -> CastBlock.NeedsRestart
        else -> null
    }

    /** Whether the cast setting applies to [version] at all, so the Engines tab shows it. */
    fun supports(version: String): Boolean = Releases.compareVersions(version, EngineSettings.CAST_MIN_VERSION) >= 0
}
