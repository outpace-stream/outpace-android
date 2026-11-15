package io.github.outpacestream.android.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.core.CastMessage
import io.github.outpacestream.android.core.CastTarget
import io.github.outpacestream.android.core.Endpoint
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.EngineChoices
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.EngineTarget
import io.github.outpacestream.android.core.LogRing
import io.github.outpacestream.android.core.StartView
import io.github.outpacestream.android.core.StreamInfo
import io.github.outpacestream.android.core.StreamRef
import java.util.concurrent.CopyOnWriteArraySet

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Running : EngineState
    data object Stopping : EngineState
    data class Crashed(val reason: String) : EngineState

    /** A remote engine the app is connected to stopped answering; the connection keeps retrying. */
    data class Unreachable(val reason: String) : EngineState
}

/**
 * A cast this app started on a DLNA TV through [endpoint]'s engine. Compared by identity: each
 * `play` makes a new one, so a poll for an earlier cast cannot touch a newer one.
 */
class CastSession(val targetId: String, val targetName: String, val ref: StreamRef, val endpoint: Endpoint, val startedAt: Long)

/** A cast that ended, and why; [message] is null when the user stopped it. */
data class CastEnd(val session: CastSession, val message: CastMessage?)

/** The start of [ref] the service is following, or followed last; [view] null shows nothing. */
data class StartProgress(val ref: StreamRef, val view: StartView?, val done: Boolean)

/** Process-wide view of the engine, shared by the service and the screens. */
object EngineController {
    const val LOG_LINES = 500

    val log = LogRing(LOG_LINES)

    /** The running session's own state; the service and the selection logic use this one. */
    @Volatile var sessionState: EngineState = EngineState.Stopped
        private set

    /**
     * The state screens show and act on: [sessionState], but Stopping while the session still
     * belongs to an engine the user has switched away from and its queued stop has not run yet.
     */
    val state: EngineState get() = if (stale()) EngineState.Stopping else sessionState

    @Volatile var streams: List<StreamInfo> = emptyList()
        private set

    @Volatile var runningVersion: String? = null
        private set

    /** The settings the running engine started with; saved settings apply only after a restart. */
    @Volatile var runningSettings: EngineSettings? = null
        private set

    /** What the service runs or is connected to now; null while stopped. */
    @Volatile var sessionTarget: EngineTarget? = null
        private set

    /** [sessionTarget], hidden while it is not the selected engine (see [state]). */
    val target: EngineTarget? get() = if (stale()) null else sessionTarget

    /** Where the current session answers; null while stopped. */
    @Volatile private var sessionEndpoint: Endpoint? = null

    /** Where the current engine answers; null while stopped or while it is not the selected one. */
    val endpoint: Endpoint? get() = if (stale()) null else sessionEndpoint

    /** The selection as last written through [EngineSelection]; unknown until the first write. */
    @Volatile private var selected: EngineChoice? = null
    @Volatile private var selectionKnown = false

    /** The connected remote engine's version, when it reports one. */
    @Volatile var remoteVersion: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Screens currently showing engine status; while > 0 the service polls quickly. */
    @Volatile var visibleScreens = 0
        private set

    @Volatile internal var pollNow: (() -> Unit)? = null

    @Volatile internal var watchStartHook: ((StreamRef) -> Unit)? = null

    /** The stream start the service follows after a hand-off to a player; see [watchStart]. */
    @Volatile var startProgress: StartProgress? = null
        private set

    /**
     * Follows [ref]'s start for a bounded time after it was handed to a player, so the play screen
     * and the notification can show its progress and why it failed. A no-op while no service runs.
     */
    fun watchStart(ref: StreamRef) {
        watchStartHook?.invoke(ref)
    }

    /** The service instance allowed to publish [startProgress]; null once it is destroyed. */
    private var startOwner: Any? = null

    /** Makes [owner] (a newly created service) the only publisher of [startProgress]. */
    @Synchronized
    internal fun claimStartProgress(owner: Any) {
        startOwner = owner
    }

    /**
     * Publishes [progress] unless [owner] no longer owns it, so a watch tick still in flight when
     * its service is destroyed cannot republish after [releaseStartProgress]. [then] (a
     * notification update, never network work) runs under the same lock, so it cannot repost the
     * notification after the release either.
     */
    @Synchronized
    internal fun publishStartProgress(owner: Any, progress: StartProgress?, then: (() -> Unit)? = null) {
        if (owner !== startOwner) return
        startProgress = progress
        notifyListeners()
        then?.invoke()
    }

    /** Clears [startProgress] and stops [owner] publishing it; for the service's onDestroy. */
    @Synchronized
    internal fun releaseStartProgress(owner: Any) {
        if (owner !== startOwner) return
        startOwner = null
        startProgress = null
        notifyListeners()
    }

    /** The cast the service follows; null when nothing is casting. One TV at a time. */
    @Volatile var cast: CastSession? = null
        private set

    /** What the last status poll of [cast] said. */
    @Volatile var castMessage: CastMessage = CastMessage.Starting
        private set

    /** The last cast that ended, so the cast screen can say why. */
    @Volatile var castEnded: CastEnd? = null
        private set

    /** Cast screens showing; while > 0 the service polls the cast's status quickly. */
    @Volatile var castScreens = 0
        private set

    /** The cast a Stop was sent for and has not finished; its Stop controls stay disabled meanwhile. */
    @Volatile var castStopping: CastSession? = null
        private set

    /** The TV a `play` is being sent to right now, so a reopened cast screen can show it. */
    @Volatile var castPending: String? = null
        private set

    @Volatile internal var castHook: ((CastSession) -> Unit)? = null
    @Volatile internal var castStopHook: (() -> Unit)? = null
    @Volatile internal var castPlayHook: ((Endpoint, CastTarget, StreamRef, (Throwable?) -> Unit) -> Unit)? = null
    @Volatile internal var castPollNow: (() -> Unit)? = null

    /** Refreshes the service's notification when a cast ends without an explicit `then`. */
    @Volatile internal var castNotify: (() -> Unit)? = null

    /**
     * Sends [ref] to [target] through the service, which owns every cast request: play and stop run
     * in order on one thread, so a Stop sent after a pick also stops what that pick started, and an
     * accepted cast is tracked even when the screen that asked is gone. [done] runs on that thread.
     */
    fun playCast(endpoint: Endpoint, target: CastTarget, ref: StreamRef, done: (Throwable?) -> Unit) {
        val hook = castPlayHook
        if (hook != null) hook(endpoint, target, ref, done) else done(IllegalStateException("engine service not running"))
    }

    /** Set when Stop is pressed while a `play` is in flight and no cast is tracked yet. */
    private var pendingStop = false

    internal fun setCastPending(name: String?) {
        synchronized(this) {
            castPending = name
            if (name != null) pendingStop = false
        }
        notifyListeners()
    }

    /** Whether Stop was pressed for the `play` in flight; reading it clears it. Callers hold this lock. */
    @Synchronized
    internal fun takePendingStop(): Boolean = pendingStop.also { pendingStop = false }

    /** Records a cast the engine just accepted and lets the service follow it. */
    internal fun castStarted(session: CastSession) {
        synchronized(this) {
            cast = session
            castMessage = CastMessage.Starting
            castEnded = null
        }
        notifyListeners()
        castHook?.invoke(session)
    }

    /** Stops the current cast on its TV, through the service; without a service it is just forgotten. */
    fun stopCast() {
        val current: CastSession?
        synchronized(this) {
            current = cast
            when {
                current != null -> castStopping = current
                // Switching TVs: the old cast is gone and the new one not tracked yet. The play in
                // flight reads this under the same lock and stops what it started.
                castPending != null -> pendingStop = true
                else -> return
            }
        }
        notifyListeners()
        if (current == null) return
        val hook = castStopHook
        if (hook != null) hook() else endCast(current, null)
    }

    fun castScreenShown() {
        castScreens++
        castPollNow?.invoke()
    }

    fun castScreenHidden() {
        castScreens = maxOf(0, castScreens - 1)
    }

    /**
     * Publishes [message] for [session] unless another cast replaced it. [then] (a notification
     * update) runs under the same lock, so it cannot repost after [endCast].
     */
    @Synchronized
    internal fun publishCastMessage(session: CastSession, message: CastMessage, then: (() -> Unit)? = null) {
        if (cast !== session || castMessage == message) return
        castMessage = message
        notifyListeners()
        then?.invoke()
    }

    /** A Stop for [session] did not reach the engine: the cast stays, and Stop can be pressed again. */
    @Synchronized
    internal fun castStopFailed(session: CastSession, then: (() -> Unit)? = null) {
        if (cast !== session) return
        if (castStopping === session) castStopping = null
        castMessage = CastMessage.StopFailed
        notifyListeners()
        then?.invoke()
    }

    /** Ends [session] if it is still the current cast; false when it was already replaced or ended. */
    @Synchronized
    internal fun endCast(session: CastSession, message: CastMessage?, then: (() -> Unit)? = null): Boolean {
        if (cast !== session) return false
        cast = null
        if (castStopping === session) castStopping = null
        castEnded = CastEnd(session, message)
        notifyListeners()
        (then ?: castNotify)?.invoke()
        return true
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    fun screenShown() {
        visibleScreens++
        pollNow?.invoke()
    }

    fun screenHidden() {
        visibleScreens = maxOf(0, visibleScreens - 1)
    }

    internal fun update(
        state: EngineState? = null,
        streams: List<StreamInfo>? = null,
        version: String? = null,
        settings: EngineSettings? = null,
        clearVersion: Boolean = false,
        target: EngineTarget? = null,
        endpoint: Endpoint? = null,
        remoteVersion: String? = null,
    ) {
        if (clearVersion) {
            runningVersion = null
            runningSettings = null
            sessionTarget = null
            sessionEndpoint = null
            this.remoteVersion = null
        }
        if (target != null) sessionTarget = target
        if (endpoint != null) sessionEndpoint = endpoint
        if (remoteVersion != null) this.remoteVersion = remoteVersion
        if (state != null) sessionState = state
        if (streams != null) this.streams = streams
        if (version != null) runningVersion = version
        if (settings != null) runningSettings = settings
        notifyListeners()
    }

    /** Records the selection [EngineSelection] just stored, so [state] can tell a lingering session apart. */
    @Synchronized
    internal fun selectionChanged(choice: EngineChoice?) {
        val changed = !selectionKnown || selected != choice
        selected = choice
        selectionKnown = true
        if (changed) notifyListeners()
    }

    /**
     * A selection read from storage, published only while no write has been seen: a read on a
     * background thread can predate a newer write and must not replace it.
     */
    @Synchronized
    internal fun selectionObserved(choice: EngineChoice?) {
        if (selectionKnown) return
        selected = choice
        selectionKnown = true
        notifyListeners()
    }

    private fun stale(): Boolean = selectionKnown && !EngineChoices.sessionMatches(sessionTarget, selected)

    /** Why the last start or connection failed, when it did. */
    fun failureReason(): String? = when (val s = state) {
        is EngineState.Crashed -> s.reason
        is EngineState.Unreachable -> s.reason
        else -> null
    }

    /** Settings that describe where the engine is reachable now: the running ones, else the saved ones. */
    fun effectiveSettings(saved: EngineSettings): EngineSettings = runningSettings ?: saved

    /**
     * Where requests and players reach the engine: the current session's address, else the
     * selected engine's (loopback on the saved port for a local one); null when nothing is selected.
     */
    fun endpointFor(context: Context): Endpoint? {
        endpoint?.let { return it }
        return when (val c = EngineSelection.current(context)) {
            null -> null
            is EngineChoice.Local -> Endpoint.loopback(Prefs(context).settings.port)
            is EngineChoice.Remote -> EngineSelection.remote(context)?.endpoint
        }
    }

    /** True while the current or selected engine is a remote one. */
    fun isRemote(context: Context): Boolean = target is EngineTarget.Remote || EngineSelection.current(context) is EngineChoice.Remote

    internal fun notifyListeners() {
        main.post { listeners.forEach { it() } }
    }

    fun start(context: Context) {
        // Mark the start now, so a waiter never mistakes an earlier crash for this attempt's result.
        val s = sessionState
        if (s != EngineState.Running && s != EngineState.Starting) update(EngineState.Starting)
        send(context, EngineService.ACTION_START)
    }

    fun restart(context: Context) = send(context, EngineService.ACTION_RESTART)

    fun stop(context: Context) {
        if (sessionState == EngineState.Stopped) return
        send(context, EngineService.ACTION_STOP)
    }

    /** Blocks (background threads only) until the engine runs or fails; true when running. */
    fun awaitRunning(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (state) {
                EngineState.Running -> return true
                is EngineState.Crashed, is EngineState.Unreachable -> return false
                else -> Thread.sleep(100)
            }
        }
        return state == EngineState.Running
    }

    private fun send(context: Context, action: String) {
        val intent = Intent(context, EngineService::class.java).setAction(action)
        if (action != EngineService.ACTION_STOP && Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
