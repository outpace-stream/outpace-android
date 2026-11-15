package io.github.outpacestream.android.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.github.outpacestream.android.EngineFiles
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.CastMessage
import io.github.outpacestream.android.core.CastWatch
import io.github.outpacestream.android.core.Endpoint
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.EngineTarget
import io.github.outpacestream.android.core.Launch
import io.github.outpacestream.android.core.RemoteEngine
import io.github.outpacestream.android.core.RemoteHealth
import io.github.outpacestream.android.core.StartMessage
import io.github.outpacestream.android.core.StartStatusJson
import io.github.outpacestream.android.core.StartStep
import io.github.outpacestream.android.core.StartView
import io.github.outpacestream.android.core.StartWatch
import io.github.outpacestream.android.core.StreamInfo
import io.github.outpacestream.android.core.StreamRef
import io.github.outpacestream.android.core.StreamUrls
import io.github.outpacestream.android.core.StreamsJson
import io.github.outpacestream.android.core.TimeBudget
import io.github.outpacestream.android.core.VersionJson
import io.github.outpacestream.android.net.CastClient
import io.github.outpacestream.android.net.CastException
import io.github.outpacestream.android.net.Http
import io.github.outpacestream.android.net.HttpStatusException
import io.github.outpacestream.android.ui.MainActivity
import io.github.outpacestream.android.ui.castText
import io.github.outpacestream.android.ui.startText
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the engine process, or the connection to a remote engine. All engine work runs on one
 * worker thread, so start, stop, exit handling and polling never race each other.
 */
class EngineService : Service() {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-worker") }
    private var process: Process? = null
    private var port = 0

    /** The remote engine this service is connected to; null in local mode. */
    private var remote: RemoteEngine? = null

    /** Bumped on every start and stop, so a poll scheduled for an earlier session drops its result. */
    private var generation = 0

    /** The startId of the command the worker is handling; [finish] stops only for the newest one. */
    @Volatile private var commandId = 0
    private var stopping = false
    private var autoRestarted = false
    private var idleSince = 0L
    private var poll: ScheduledFuture<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shownStreams = -1

    /** The stream start being followed after a hand-off, and its next status poll; see [watchStart]. */
    private var watch: StartWatch? = null
    private var watchTask: ScheduledFuture<*>? = null

    /**
     * Follows an active cast's status. Its own thread, because a status request may wait seconds for
     * the TV and must not hold up engine start and stop; it only gets a thread once something casts.
     */
    private val castWorker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cast-poll") }
    @Volatile private var castTask: ScheduledFuture<*>? = null

    /** The decision state of the followed cast; castWorker only. */
    private var castWatch: CastWatch? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        EngineController.pollNow = { worker.execute { schedulePoll(0) } }
        EngineController.claimStartProgress(this)
        EngineController.watchStartHook = { ref -> worker.execute { watchStart(ref) } }
        EngineController.castHook = { session -> castWorker.execute { followCast(session) } }
        EngineController.castStopHook = { castWorker.execute { stopCast() } }
        EngineController.castPollNow = { castWorker.execute { EngineController.cast?.let { scheduleCast(it, 0) } } }
        EngineController.castNotify = ::updateNotification
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                goForeground()
                worker.execute {
                    commandId = startId
                    autoRestarted = false
                    startEngine()
                }
            }
            ACTION_RESTART -> {
                goForeground()
                worker.execute {
                    commandId = startId
                    stopEngine()
                    autoRestarted = false
                    startEngine()
                }
            }
            ACTION_STOP -> worker.execute {
                commandId = startId
                stopEngine()
                finish()
            }
            ACTION_CAST_STOP -> {
                castWorker.execute { stopCast() }
                // A stale notification can start a service with no engine; it has nothing to keep running.
                worker.execute {
                    if (process == null && remote == null && EngineController.sessionState == EngineState.Stopped) {
                        commandId = startId
                        finish()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        EngineController.pollNow = null
        EngineController.watchStartHook = null
        EngineController.castHook = null
        EngineController.castStopHook = null
        EngineController.castPollNow = null
        EngineController.castNotify = null
        worker.shutdownNow()
        castWorker.shutdownNow()
        EngineController.cast?.let { EngineController.endCast(it, CastMessage.Ended) }
        // shutdownNow does not wait for a tick in flight; releasing ownership stops it publishing.
        EngineController.releaseStartProgress(this)
        process?.let { terminate(it) }
        process = null
        releaseWakeLock()
        if (EngineController.sessionState != EngineState.Stopped && EngineController.sessionState !is EngineState.Crashed) {
            EngineController.update(EngineState.Stopped, emptyList(), clearVersion = true)
        }
        super.onDestroy()
    }

    // Worker thread only below this line, except goForeground and finish.

    private fun startEngine() {
        if (process?.isAliveCompat() == true) return
        // Already connected: check now, so a waiter learns at once whether the remote answers.
        if (remote != null) return schedulePoll(0)
        when (val choice = EngineSelection.current(this)) {
            is EngineChoice.Local -> startLocal(choice.version)
            is EngineChoice.Remote -> EngineSelection.remote(this)?.let(::startRemote) ?: noSelection()
            null -> noSelection()
        }
    }

    private fun noSelection() {
        EngineController.update(EngineState.Crashed(getString(R.string.no_engine_selected)), emptyList(), clearVersion = true)
        finish()
    }

    /** Connects to [engine]: no process, just the poll loop against its address. */
    private fun startRemote(engine: RemoteEngine) {
        generation++
        remote = engine
        Prefs(this).remoteConnected = true
        EngineController.log.add("[app] connecting to ${engine.name} at ${engine.endpoint.display}")
        EngineController.update(
            EngineState.Starting, emptyList(), clearVersion = true,
            target = EngineTarget.Remote(engine), endpoint = engine.endpoint,
        )
        updateNotification()
        schedulePoll(0)
    }

    private fun startLocal(version: String) {
        generation++
        val prefs = Prefs(this)
        val settings = prefs.settings
        port = settings.port
        val binary = EngineFiles.store(this).binary(version)
        val data = EngineFiles.dataDir(this).apply { mkdirs() }
        val cache = EngineFiles.cacheDir(this).apply { mkdirs() }
        EngineController.update(
            EngineState.Starting, emptyList(), version, settings,
            target = EngineTarget.Local(version), endpoint = Endpoint.loopback(port),
        )
        updateNotification()
        val proc = try {
            val command = Launch.command(Build.VERSION.SDK_INT, Launch.elfIs64(binary), binary.path)
            EngineController.log.add("[app] starting outpace $version: ${command.joinToString(" ")}")
            ProcessBuilder(command).redirectErrorStream(true).directory(data).also { pb ->
                pb.environment().putAll(settings.env(data.path, cache.path, EngineSettings.proxySupported(version)))
            }.start()
        } catch (e: Exception) {
            fail(getString(R.string.engine_failed_to_start, e.message ?: e.javaClass.simpleName))
            return
        }
        process = proc
        Thread({ pump(proc) }, "engine-log").start()
        Thread({
            val code = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
            try { worker.execute { onExit(proc, code) } } catch (_: Exception) {}
        }, "engine-wait").start()

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && process === proc && proc.isAliveCompat()) {
            val healthy = try { Http.get(StreamUrls.health(Endpoint.loopback(port)), readTimeout = 2_000).trim() == "ok" } catch (_: IOException) { false }
            if (healthy) {
                EngineController.log.add("[app] engine is up on port $port")
                idleSince = System.currentTimeMillis()
                EngineController.update(EngineState.Running)
                updateNotification()
                schedulePoll(0)
                return
            }
            Thread.sleep(250)
        }
        if (process === proc) {
            if (proc.isAliveCompat()) {
                terminate(proc)
                process = null
                fail((START_TIMEOUT_MS / 1000).toInt().let { resources.getQuantityString(R.plurals.engine_no_answer, it, it) })
            }
            // Otherwise the process exited during startup; onExit reports it.
        }
    }

    private fun pump(proc: Process) {
        try {
            proc.inputStream.bufferedReader().forEachLine { EngineController.log.add(it) }
        } catch (_: IOException) {
            // The stream closes when the engine exits.
        }
    }

    private fun onExit(proc: Process, code: Int) {
        if (proc !== process) return
        process = null
        cancelPoll()
        endWatch()
        endCastSession(stopTv = false)
        releaseWakeLock()
        if (stopping) return
        EngineController.log.add("[app] engine exited with code $code")
        if (!autoRestarted) {
            autoRestarted = true
            EngineController.log.add("[app] restarting once")
            startEngine()
        } else {
            fail(getString(R.string.engine_exited, code))
        }
    }

    private fun stopEngine() {
        if (remote != null) {
            disconnect()
            return
        }
        val proc = process ?: return
        generation++
        stopping = true
        EngineController.update(EngineState.Stopping)
        updateNotification()
        cancelPoll()
        endWatch()
        endCastSession(stopTv = true)
        terminate(proc)
        process = null
        stopping = false
        releaseWakeLock()
        EngineController.log.add("[app] engine stopped")
        EngineController.update(EngineState.Stopped, emptyList(), clearVersion = true)
    }

    private fun disconnect() {
        val engine = remote ?: return
        generation++
        remote = null
        cancelPoll()
        endWatch()
        endCastSession(stopTv = true)
        Prefs(this).remoteConnected = false
        EngineController.log.add("[app] disconnected from ${engine.name}")
        EngineController.update(EngineState.Stopped, emptyList(), clearVersion = true)
    }

    private fun fail(reason: String) {
        endWatch()
        endCastSession(stopTv = false)
        EngineController.log.add("[app] $reason")
        EngineController.update(EngineState.Crashed(reason), emptyList(), clearVersion = true)
        finish()
    }

    private fun schedulePoll(delayMs: Long) {
        cancelPoll()
        if (remote == null && EngineController.sessionState != EngineState.Running) return
        val gen = generation
        poll = worker.schedule({ if (gen == generation) poll() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun poll() {
        val engine = remote
        if (engine == null) pollStreams() else pollRemote(engine)
    }

    private fun pollInterval() = if (EngineController.visibleScreens > 0) UI_POLL_MS else BACKGROUND_POLL_MS

    /**
     * One remote poll: the health check decides connected or unreachable, then streams as for a
     * local engine. No idle stop and no wake lock: the phone holds no process to keep alive.
     */
    private fun pollRemote(engine: RemoteEngine) {
        // Runs on the single worker, like every start and stop, so the session cannot change
        // mid-poll; schedulePoll's generation check drops polls queued for an earlier session.
        val error = remoteHealthError(engine.endpoint)
        if (error != null) {
            if (EngineController.sessionState !is EngineState.Unreachable) {
                EngineController.log.add("[app] ${engine.name} is not answering: $error")
            }
            EngineController.update(EngineState.Unreachable(error), emptyList())
            updateNotification()
        } else {
            if (EngineController.sessionState != EngineState.Running) {
                val version = try { VersionJson.parse(Http.getWithin(StreamUrls.version(engine.endpoint), VERSION_MS)) } catch (_: Exception) { null }
                EngineController.log.add("[app] connected to ${engine.name}" + (version?.let { ", v$it" } ?: ""))
                EngineController.update(EngineState.Running, remoteVersion = version)
                updateNotification()
            }
            val streams = fetchStreams(engine.endpoint)
            if (streams != null) {
                EngineController.update(streams = streams)
                if (streams.size != shownStreams) updateNotification()
            }
        }
        schedulePoll(pollInterval())
    }

    /** Null when [e] answers `/healthz` like an Outpace engine, else why not. */
    private fun remoteHealthError(e: Endpoint): String? = try {
        if (RemoteHealth.isOk(Http.getWithin(StreamUrls.health(e), HEALTH_MS))) null else getString(R.string.remote_not_outpace)
    } catch (ex: Exception) {
        ex.message ?: ex.javaClass.simpleName
    }

    private fun fetchStreams(e: Endpoint): List<StreamInfo>? = try {
        StreamsJson.parse(Http.getWithin(StreamUrls.streams(e), HEALTH_MS))
    } catch (_: Exception) {
        null
    }?.let { if (EngineController.visibleScreens > 0) withStatus(e, it) else it }

    private fun cancelPoll() {
        poll?.cancel(false)
        poll = null
    }

    private fun pollStreams() {
        if (EngineController.sessionState != EngineState.Running) return
        val streams = try {
            StreamsJson.parse(Http.get(StreamUrls.streams(Endpoint.loopback(port)), readTimeout = 3_000))
        } catch (_: Exception) {
            null
        }?.let { if (EngineController.visibleScreens > 0) withStatus(Endpoint.loopback(port), it) else it }
        if (streams != null) {
            EngineController.update(streams = streams)
            val now = System.currentTimeMillis()
            if (streams.isEmpty()) {
                releaseWakeLock()
                if (Prefs(this).idleStop && now - idleSince >= Prefs.IDLE_STOP_MS) {
                    EngineController.log.add("[app] no streams for ${Prefs.IDLE_STOP_MS / 60_000} minutes, stopping")
                    stopEngine()
                    finish()
                    return
                }
            } else {
                idleSince = now
                acquireWakeLock()
            }
            if (streams.size != shownStreams) updateNotification()
        }
        schedulePoll(pollInterval())
    }

    /**
     * Peers and buffer for the first few streams, fetched only while a screen shows them so the
     * background poll stays one request. A stream whose status fails is shown without them.
     */
    private fun withStatus(endpoint: Endpoint, streams: List<StreamInfo>): List<StreamInfo> {
        // These requests share the lifecycle worker, so they get one bounded budget per poll, and each
        // has a hard total deadline; a stop or restart waits at most about STATUS_BUDGET_MS.
        val budget = TimeBudget(STATUS_BUDGET_MS)
        return streams.mapIndexed { i, st ->
            if (i >= MAX_STATUS_STREAMS) return@mapIndexed st
            val timeout = budget.next(STATUS_REQUEST_MS, STATUS_MIN_MS) ?: return@mapIndexed st
            try {
                val url = StreamUrls.status(endpoint, st.network, st.id)
                StreamsJson.withStatus(st, Http.getWithin(url, timeout))
            } catch (_: Exception) {
                st
            }
        }
    }

    /**
     * Follows [ref]'s start after a hand-off to a player: one status request about once a second,
     * each with a hard deadline, until [StartWatch] reports playing, failed or out of time. This is
     * the one poll that may run with no screen visible, because the user has just left for the
     * player; it is bounded by [StartWatch.WATCH_MS] and replaced by a newer hand-off.
     */
    private fun watchStart(ref: StreamRef) {
        endWatch()
        if (remote == null && EngineController.sessionState != EngineState.Running) return
        val w = StartWatch(SystemClock.elapsedRealtime())
        watch = w
        EngineController.publishStartProgress(this, StartProgress(ref, StartView(StartMessage.Waiting), done = false), ::updateNotification)
        scheduleWatch(w, ref, 0)
    }

    private fun scheduleWatch(w: StartWatch, ref: StreamRef, delayMs: Long) {
        val gen = generation
        watchTask = worker.schedule({ if (gen == generation && watch === w) watchTick(w, ref) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun watchTick(w: StartWatch, ref: StreamRef) {
        val endpoint = remote?.endpoint ?: Endpoint.loopback(port)
        val step = try {
            val json = Http.getWithin(StreamUrls.status(endpoint, "ace", ref.selector), STATUS_REQUEST_MS)
            w.status(StartStatusJson.parse(json), SystemClock.elapsedRealtime())
        } catch (e: HttpStatusException) {
            if (e.code == 404) w.notFound(SystemClock.elapsedRealtime()) else w.unreachable(SystemClock.elapsedRealtime())
        } catch (_: Exception) {
            w.unreachable(SystemClock.elapsedRealtime())
        }
        show(ref, step)
        if (!step.done) return scheduleWatch(w, ref, StartWatch.POLL_MS)
        watch = null
        // A failure or the offline hint stays in the notification for a while, then the usual text returns.
        if (step.view != null) {
            val gen = generation
            watchTask = worker.schedule({ if (gen == generation && watch == null) clearStartProgress() }, RESULT_SHOWN_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun show(ref: StreamRef, step: StartStep) {
        val before = EngineController.startProgress
        val now = StartProgress(ref, step.view, step.done)
        if (before == now) return
        EngineController.publishStartProgress(this, now, if (before?.view != now.view) ::updateNotification else null)
    }

    private fun clearStartProgress() {
        if (EngineController.startProgress == null) return
        EngineController.publishStartProgress(this, null, ::updateNotification)
    }

    private fun endWatch() {
        watchTask?.cancel(false)
        watchTask = null
        watch = null
        if (EngineController.startProgress != null) EngineController.publishStartProgress(this, null)
    }

    /**
     * Ends the followed cast because the engine session ends. With [stopTv], first tells the TV to
     * stop (one bounded request), since the stream it plays is about to go away.
     */
    private fun endCastSession(stopTv: Boolean) {
        val session = EngineController.cast ?: return
        castTask?.cancel(false)
        if (stopTv) {
            try { CastClient.stop(session.endpoint, session.targetId) } catch (_: Exception) {}
        }
        EngineController.endCast(session, null, ::updateNotification)
    }

    // castWorker only below, up to terminate.

    private fun followCast(session: CastSession) {
        castTask?.cancel(false)
        if (EngineController.cast !== session) return
        castWatch = CastWatch(session.startedAt)
        EngineController.log.add("[app] casting to ${session.targetName}")
        updateNotification()
        scheduleCast(session, castInterval())
    }

    private fun castInterval() = if (EngineController.castScreens > 0) CastWatch.VISIBLE_POLL_MS else CastWatch.BACKGROUND_POLL_MS

    private fun scheduleCast(session: CastSession, delayMs: Long) {
        castTask?.cancel(false)
        if (EngineController.cast !== session || castWatch == null) return
        castTask = castWorker.schedule({ castTick(session) }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** One status poll of the cast; [CastWatch] decides what it means. */
    private fun castTick(session: CastSession) {
        val w = castWatch ?: return
        if (EngineController.cast !== session) return
        val step = try {
            w.status(CastClient.status(session.endpoint, session.targetId), SystemClock.elapsedRealtime())
        } catch (e: CastException) {
            w.failure(e.error.failure)
        } catch (_: Exception) {
            w.unreachable()
        }
        if (step.ended) {
            castWatch = null
            if (EngineController.endCast(session, step.message, ::updateNotification)) {
                EngineController.log.add("[app] cast to ${session.targetName} ended: ${step.message}")
            }
            return
        }
        EngineController.publishCastMessage(session, step.message, ::updateNotification)
        scheduleCast(session, castInterval())
    }

    /** Stops the cast on its TV. The engine retires the stream URL even when the TV does not answer. */
    private fun stopCast() {
        val session = EngineController.cast ?: return
        castTask?.cancel(false)
        castWatch = null
        val failed = try {
            CastClient.stop(session.endpoint, session.targetId)
            null
        } catch (e: CastException) {
            // The TV or the engine forgot this cast already; either way it is over.
            null
        } catch (e: Exception) {
            CastMessage.EngineUnreachable
        }
        EngineController.log.add("[app] stopped casting to ${session.targetName}")
        EngineController.endCast(session, failed, ::updateNotification)
    }

    private fun terminate(proc: Process) {
        if (Build.VERSION.SDK_INT < 24) {
            // Older libcore may implement destroy() as SIGKILL, which skips the engine's shutdown.
            pidOf(proc)?.let { android.os.Process.sendSignal(it, SIGTERM) } ?: proc.destroy()
        } else {
            proc.destroy()
        }
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (proc.isAliveCompat() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (proc.isAliveCompat()) {
            EngineController.log.add("[app] engine did not stop in time, killing it")
            if (Build.VERSION.SDK_INT >= 26) proc.destroyForcibly() else pidOf(proc)?.let(android.os.Process::killProcess) ?: proc.destroy()
        }
    }

    private fun pidOf(proc: Process): Int? = try {
        proc.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(proc)
    } catch (_: Exception) {
        null
    }

    /** Re-armed on every poll while streams exist, so a missed release can never hold it for long. */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "outpace:streams")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    /**
     * Stops the service unless a newer command arrived after the one being handled: a START queued
     * right behind a STOP must not be dropped by the STOP's teardown.
     */
    private fun finish() {
        if (!stopSelfResult(commandId)) return
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun updateNotification() {
        shownStreams = EngineController.streams.size
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_engine), NotificationManager.IMPORTANCE_LOW))
        }
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable)
        val stop = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP), immutable)
        val version = EngineController.runningVersion?.let { "v$it" } ?: ""
        val n = EngineController.streams.size
        val connected = (EngineController.sessionTarget as? EngineTarget.Remote)?.engine
        val start = EngineController.startProgress?.view
        val cast = EngineController.cast
        val text = if (start != null && EngineController.sessionState == EngineState.Running) {
            startText(start)
        } else if (cast != null) {
            castText(EngineController.castMessage, cast.targetName)
        } else if (connected != null) {
            when (EngineController.sessionState) {
                EngineState.Running -> resources.getQuantityString(R.plurals.notification_remote, n, connected.name, n)
                is EngineState.Unreachable -> getString(R.string.notification_remote_unreachable, connected.name)
                else -> getString(R.string.state_connecting)
            }
        } else {
            when (EngineController.sessionState) {
                EngineState.Running -> resources.getQuantityString(R.plurals.notification_running, n, version, n)
                EngineState.Stopping -> getString(R.string.state_stopping)
                else -> getString(R.string.state_starting)
            }
        }
        val stopLabel = getString(if (connected != null) R.string.action_disconnect else R.string.action_stop)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
        if (cast != null) {
            val stopCast = PendingIntent.getService(this, 2, Intent(this, EngineService::class.java).setAction(ACTION_CAST_STOP), immutable)
            builder.addAction(Notification.Action.Builder(0, getString(R.string.cast_stop), stopCast).build())
        }
        return builder.addAction(Notification.Action.Builder(0, stopLabel, stop).build()).build()
    }

    companion object {
        const val ACTION_START = "io.github.outpacestream.android.START"
        const val ACTION_STOP = "io.github.outpacestream.android.STOP"
        const val ACTION_RESTART = "io.github.outpacestream.android.RESTART"
        const val ACTION_CAST_STOP = "io.github.outpacestream.android.CAST_STOP"
        private const val CHANNEL = "engine"
        private const val NOTIFICATION_ID = 1
        private const val SIGTERM = 15
        const val START_TIMEOUT_MS = 15_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val UI_POLL_MS = 2_000L
        private const val MAX_STATUS_STREAMS = 4

        /** Total time per poll for peer and buffer requests; shorter than the UI poll interval. */
        private const val STATUS_BUDGET_MS = 1_500L
        private const val STATUS_REQUEST_MS = 1_000
        private const val STATUS_MIN_MS = 100
        private const val BACKGROUND_POLL_MS = 30_000L

        /** Per-request deadline for a remote engine's health check and stream list. */
        private const val HEALTH_MS = 3_000
        private const val VERSION_MS = 2_000
        /** How long a failed start's message stays in the notification. */
        private const val RESULT_SHOWN_MS = 60_000L
        private const val WAKE_LOCK_MS = 3 * BACKGROUND_POLL_MS
    }
}

/** Process.isAlive() is API 26+. */
internal fun Process.isAliveCompat(): Boolean = try {
    exitValue()
    false
} catch (_: IllegalThreadStateException) {
    true
}
