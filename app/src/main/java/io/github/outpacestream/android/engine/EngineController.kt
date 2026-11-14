package io.github.outpacestream.android.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.core.Endpoint
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.EngineChoices
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.EngineTarget
import io.github.outpacestream.android.core.LogRing
import io.github.outpacestream.android.core.StreamInfo
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
    internal fun selectionChanged(choice: EngineChoice?) {
        val changed = !selectionKnown || selected != choice
        selected = choice
        selectionKnown = true
        if (changed) notifyListeners()
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
