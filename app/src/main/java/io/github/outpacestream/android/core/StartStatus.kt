package io.github.outpacestream.android.core

import org.json.JSONObject

/** The engine's start phase on `GET /streams/<network>/<id>/status`. */
enum class StartPhase(val wire: String) {
    Resolving("resolving"),
    Connecting("connecting"),
    Buffering("buffering"),
    Playing("playing"),
    Failed("failed"),
    ;

    companion object {
        fun parse(s: String?): StartPhase? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * One status answer. [phase] is null on engines that predate start phases, or for a phase this
 * app does not know; [errorCode] and [errorMessage] are set only for `failed`.
 */
data class StartStatus(
    val phase: StartPhase?,
    val peers: Int,
    val bufferMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    /** True when the engine reports a phase at all, even one this app does not know. */
    val hasPhase: Boolean,
)

object StartStatusJson {
    fun parse(json: String): StartStatus {
        val o = JSONObject(json)
        val phaseText = o.optString("phase").takeIf { o.has("phase") && !o.isNull("phase") }
        val error = o.optJSONObject("error")
        return StartStatus(
            phase = StartPhase.parse(phaseText),
            peers = if (o.has("peers") && !o.isNull("peers")) o.optInt("peers", 0) else 0,
            bufferMs = o.takeIf { it.has("buffer_ms") && !it.isNull("buffer_ms") }?.optLong("buffer_ms"),
            errorCode = error?.optString("code")?.takeIf { error.has("code") && !error.isNull("code") && it.isNotBlank() },
            errorMessage = error?.optString("message")?.takeIf { error.has("message") && !error.isNull("message") && it.isNotBlank() },
            hasPhase = phaseText != null,
        )
    }
}

/** What the app tells the user about a start; the UI maps each to a string resource. */
enum class StartMessage(val failure: Boolean = false, val showsDetail: Boolean = false) {
    /** Handed to the player; the engine has not seen the request yet. */
    Waiting,
    Resolving,
    Connecting,
    Buffering,

    /** Older engines: no peers for a while, so the stream may be offline. Not terminal. */
    NoPeersYet,
    NoPeers(failure = true),
    Network(failure = true),
    NotAStream(failure = true),
    Unresolvable(failure = true, showsDetail = true),
    Protected(failure = true, showsDetail = true),
    Capacity(failure = true),
    NoSpace(failure = true),
    Failed(failure = true),
    ;

    companion object {
        /** The message for an engine error code; unknown codes get the generic failure. */
        fun forCode(code: String?): StartMessage = when (code) {
            "no_peers" -> NoPeers
            "network" -> Network
            "not_found" -> NotAStream
            "unresolvable" -> Unresolvable
            "protected" -> Protected
            "capacity" -> Capacity
            "no_space" -> NoSpace
            else -> Failed
        }
    }
}

/** What to show for a start: a [message], a peer count for [StartMessage.Connecting], and engine [detail] where it helps. */
data class StartView(val message: StartMessage, val peers: Int = 0, val detail: String? = null)

/** The result of one observation: what to show (null shows nothing) and whether polling stops. */
data class StartStep(val view: StartView?, val done: Boolean)

/**
 * Follows one stream start from the hand-off to the player until it plays, fails or runs out of
 * time. Pure: the caller polls status about once a second and feeds each answer in with the time,
 * from a monotonic clock (`SystemClock.elapsedRealtime()`), never the wall clock.
 */
class StartWatch(private val startedAt: Long) {
    private var lastView: StartView? = StartView(StartMessage.Waiting)
    private var seen = false
    private var zeroPeersSince: Long? = null

    /**
     * True once the engine answered anything other than `failed` (a phase, or 404) during this
     * watch. Before that, a `failed` answer may be the 60 s retained failure of an earlier attempt
     * that the player's new request has not replaced yet.
     */
    private var sawNonFailure = false

    /** The first `failed` answer seen before [sawNonFailure], while it may still be stale. */
    private var firstFailure: Pair<String?, String?>? = null

    /** The answer was a status body. */
    fun status(s: StartStatus, now: Long): StartStep {
        if (s.hasPhase && s.phase == StartPhase.Failed && !failureIsCurrent(s, now)) {
            // Possibly an earlier attempt's retained failure: keep waiting for the new attempt.
            if (firstFailure == null) firstFailure = s.errorCode to s.errorMessage
            return keep(lastView, now)
        }
        seen = true
        if (!s.hasPhase || s.phase != StartPhase.Failed) sawNonFailure = true
        if (s.hasPhase) return modern(s, now)
        return legacy(s, now)
    }

    /**
     * A `failed` answer belongs to this hand-off when the engine reported something else first,
     * when it differs from the failure seen at first, or when it outlasts [NO_SESSION_MS]: by then
     * a new attempt would have replaced a stale record, so the same failure is this attempt's.
     */
    private fun failureIsCurrent(s: StartStatus, now: Long): Boolean {
        if (sawNonFailure) return true
        val first = firstFailure ?: return now - startedAt >= NO_SESSION_MS
        return first != (s.errorCode to s.errorMessage) || now - startedAt >= NO_SESSION_MS
    }

    /** The engine answered 404: no session and, on newer engines, no recent attempt. */
    fun notFound(now: Long): StartStep {
        sawNonFailure = true
        // A start that was running and is gone again ended without a recorded failure (the player
        // stopped, or an older engine dropped it); nothing more to report.
        if (seen) return finish(null)
        if (now - startedAt >= NO_SESSION_MS) return finish(null)
        return keep(StartView(StartMessage.Waiting), now)
    }

    /** The request itself failed (timeout, connection refused); keep the last view and retry. */
    fun unreachable(now: Long): StartStep = keep(lastView, now)

    private fun modern(s: StartStatus, now: Long): StartStep = when (s.phase) {
        StartPhase.Resolving -> keep(StartView(StartMessage.Resolving), now)
        StartPhase.Connecting -> keep(StartView(StartMessage.Connecting, s.peers), now)
        StartPhase.Buffering -> keep(StartView(StartMessage.Buffering, s.peers), now)
        StartPhase.Playing -> finish(null)
        StartPhase.Failed -> {
            val message = StartMessage.forCode(s.errorCode)
            finish(StartView(message, detail = s.errorMessage?.takeIf { message.showsDetail }?.take(MAX_DETAIL)))
        }
        // A phase added after this app was built: keep the last view until a known one arrives.
        null -> keep(lastView, now)
    }

    /** Engines without `phase`: peers only, with the "may be offline" hint after a quiet spell. */
    private fun legacy(s: StartStatus, now: Long): StartStep {
        if (s.peers > 0) {
            zeroPeersSince = null
            // Buffered media with peers connected means data flows; nothing left to report.
            if ((s.bufferMs ?: 0) > 0) return finish(null)
            return keep(StartView(StartMessage.Connecting, s.peers), now)
        }
        val since = zeroPeersSince ?: now.also { zeroPeersSince = it }
        val view = if (now - since >= NO_PEERS_HINT_MS) StartView(StartMessage.NoPeersYet) else StartView(StartMessage.Connecting, 0)
        return keep(view, now)
    }

    private fun keep(view: StartView?, now: Long): StartStep {
        lastView = view
        if (now - startedAt >= WATCH_MS) {
            // Out of time: a "may be offline" hint stays useful, a progress line would be stale.
            return finish(view?.takeIf { it.message == StartMessage.NoPeersYet })
        }
        return StartStep(view, done = false)
    }

    private fun finish(view: StartView?): StartStep {
        lastView = view
        return StartStep(view, done = true)
    }

    companion object {
        /** Poll interval while a start is followed. */
        const val POLL_MS = 1_000L

        /** Hard limit on how long one start is followed. */
        const val WATCH_MS = 90_000L

        /** Stop when the engine never reports the stream, for example because no player opened it. */
        const val NO_SESSION_MS = 30_000L

        /** Engine messages shown as detail are short fixed texts; cap them anyway. */
        const val MAX_DETAIL = 160

        /** Older engines: peers at zero this long shows "may be offline". */
        const val NO_PEERS_HINT_MS = 20_000L
    }
}
