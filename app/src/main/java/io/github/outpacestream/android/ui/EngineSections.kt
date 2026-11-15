package io.github.outpacestream.android.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.outpacestream.android.EngineFiles
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.CastGate
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.EngineChoices
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.EngineTarget
import io.github.outpacestream.android.core.HlsStart
import io.github.outpacestream.android.core.Releases
import io.github.outpacestream.android.core.RemoteEngine
import io.github.outpacestream.android.core.RemoteHealth
import io.github.outpacestream.android.core.StartupBuffer
import io.github.outpacestream.android.core.StreamUrls
import io.github.outpacestream.android.core.UploadLimit
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineSelection
import io.github.outpacestream.android.engine.EngineState
import io.github.outpacestream.android.net.Http
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two engine sections of Settings. Engine connection picks the engine that plays everything:
 * this device's engine, always first and the default, or a remote one. Local engine manages this
 * device's engine (versions, start and stop, reinstall, logs, settings) and is disabled while a
 * remote engine is selected. Remote engines are health-checked only while Settings shows.
 */
class EngineSections(private val host: MainActivity, private val root: View) {
    private sealed interface Entry {
        val key: String

        data object Local : Entry {
            override val key get() = "local"
        }

        data class Remote(val engine: RemoteEngine) : Entry {
            override val key get() = "remote:${engine.id}"
        }
    }

    private val prefs: Prefs get() = host.prefs

    private fun <T : View> find(id: Int): T = root.findViewById(id)

    private val list = find<LinearLayout>(R.id.engine_list)
    private val addRemote = find<Button>(R.id.add_remote)
    private val localTitle = find<View>(R.id.local_title)
    private val localDisabled = find<View>(R.id.local_disabled)
    private val localSection = find<ViewGroup>(R.id.local_section)
    private val localState = find<TextView>(R.id.local_state)
    private val localDetail = find<TextView>(R.id.local_detail)
    private val versions = find<Button>(R.id.versions)
    private val startStop = find<Button>(R.id.start_stop)
    private val reinstall = find<Button>(R.id.reinstall)
    private val logs = find<Button>(R.id.logs)
    private val update = find<Button>(R.id.update)
    private val restartBanner = find<View>(R.id.restart_banner)
    private val rows = SettingRows(host) { host.renderCurrent() }
    private val proxySection = find<View>(R.id.proxy_section)
    private lateinit var proxy: ProxySettings

    /** Whether the local version shown takes the proxy variables; set on every render. */
    private var proxySupported = false

    /** Whether the Local engine section currently accepts input; null until the first render. */
    private var localEnabled: Boolean? = null

    /** Shown only when the local version reads `OUTPACE_ACESTREAM_IDENTITY`. */
    private lateinit var identityRow: View
    private lateinit var castRow: View

    /** Remote id to whether its last probe answered; absent until probed. */
    private val health = ConcurrentHashMap<String, Boolean>()
    private var probes: ScheduledFuture<*>? = null

    /** Bumped on every show and hide; a sweep started for an earlier showing stops at the next remote. */
    private val probeRound = AtomicInteger()

    init {
        addRemote.setOnClickListener { RemoteDialog.show(host, null) { host.renderCurrent(); onVisible() } }
        versions.setOnClickListener { host.open(VersionsActivity::class.java) }
        update.setOnClickListener { host.open(VersionsActivity::class.java) }
        startStop.setOnClickListener { onStartStop() }
        reinstall.setOnClickListener { localVersion()?.let { VersionsActivity.reinstall(host, it) } }
        logs.setOnClickListener { host.open(LogsActivity::class.java) }
        find<Button>(R.id.restart_apply).setOnClickListener { EngineController.restart(host) }
        buildSettings()
    }

    private fun buildSettings() {
        val search = find<LinearLayout>(R.id.group_search)
        val playback = find<LinearLayout>(R.id.group_playback)
        val network = find<LinearLayout>(R.id.group_network)
        val peers = find<LinearLayout>(R.id.group_peers)
        rows.engineToggle(search, R.string.settings_search, R.string.settings_search_summary, { it.search }) { s, v -> s.copy(search = v) }
        rows.engineChoice(
            playback, R.string.settings_startup_buffer, R.string.settings_startup_buffer_summary,
            listOf(
                StartupBuffer.SMOOTH to R.string.settings_startup_buffer_smooth,
                StartupBuffer.BALANCED to R.string.settings_startup_buffer_balanced,
                StartupBuffer.FAST to R.string.settings_startup_buffer_fast,
                StartupBuffer.OFF to R.string.settings_startup_buffer_off,
            ),
            { it.startupBuffer },
        ) { s, v -> s.copy(startupBuffer = v) }
        rows.engineChoice(
            playback, R.string.settings_hls_start, R.string.settings_hls_start_summary,
            listOf(HlsStart.DEFAULT to R.string.settings_hls_start_default, HlsStart.FAST to R.string.settings_hls_start_fast),
            { it.hlsStart },
        ) { s, v -> s.copy(hlsStart = v) }
        rows.engineToggle(playback, R.string.settings_catalog_fallback, R.string.settings_catalog_fallback_summary, { it.catalogFallback }) { s, v ->
            s.copy(catalogFallback = v)
        }
        rows.engineToggle(playback, R.string.settings_low_memory, R.string.settings_low_memory_summary, { it.lowMemory }) { s, v ->
            s.copy(lowMemory = v)
        }
        // LAN access goes above the port, which is already in the layout.
        rows.engineToggle(network, R.string.settings_lan, R.string.settings_lan_warning, { it.lan }, index = 0) { s, v -> s.copy(lan = v) }
        rows.portField(find<EditText>(R.id.port), { it.port }) { s, v -> s.copy(port = v) }
        // Casting needs LAN access: turning it on with LAN access off explains why and offers it.
        castRow = rows.toggle(network, R.string.settings_cast, R.string.settings_cast_summary, { prefs.settings.cast }) { v ->
            prefs.settings = prefs.settings.copy(cast = v)
            if (v && !prefs.settings.lan) CastSetup.confirmLan(host) { rows.refresh(); host.renderCurrent() }
        }
        rows.engineToggle(peers, R.string.settings_seeding, R.string.settings_seeding_summary, { it.seeding }) { s, v -> s.copy(seeding = v) }
        rows.engineChoice(
            peers, R.string.settings_upload_limit, R.string.settings_upload_limit_summary,
            listOf(
                UploadLimit.NORMAL to R.string.settings_upload_limit_normal,
                UploadLimit.LOW to R.string.settings_upload_limit_low,
                UploadLimit.MINIMAL to R.string.settings_upload_limit_minimal,
            ),
            { it.uploadLimit },
        ) { s, v -> s.copy(uploadLimit = v) }
        proxy = ProxySettings(host, find(R.id.group_proxy)) {
            rows.applyLocks()
            host.renderCurrent()
        }
        // With the proxy always on, the engine refuses inbound peers and port mapping.
        val proxyLock = { if (prefs.settings.alwaysProxied(proxySupported)) R.string.settings_locked_by_proxy else null }
        rows.engineToggle(peers, R.string.settings_inbound, R.string.settings_inbound_summary, { it.inbound }, locked = proxyLock) { s, v ->
            s.copy(inbound = v)
        }
        rows.engineToggle(peers, R.string.settings_port_mapping, R.string.settings_port_mapping_summary, { it.portMapping }, locked = proxyLock) { s, v ->
            s.copy(portMapping = v)
        }
        rows.portRow(peers, R.string.settings_peer_port, R.string.settings_peer_port_summary, { it.peerPort }) { s, v -> s.copy(peerPort = v) }
        rows.engineToggle(peers, R.string.settings_ace_compat, R.string.settings_ace_compat_summary, { it.aceCompat }) { s, v -> s.copy(aceCompat = v) }
        identityRow = rows.engineToggle(peers, R.string.settings_acestream_identity, R.string.settings_acestream_identity_summary, { it.acestreamIdentity }) { s, v ->
            s.copy(acestreamIdentity = v)
        }
    }

    fun focusTarget(): View = list.getChildAt(0) ?: addRemote

    fun onShown() {
        rows.refresh()
        proxy.refresh()
        // refresh() re-enables locked rows' switches; a disabled section must stay disabled.
        localEnabled = null
    }

    fun onVisible() {
        probes?.cancel(false)
        val round = probeRound.incrementAndGet()
        probes = prober.scheduleWithFixedDelay({ probeRemotes(round) }, 0, PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun onInvisible() {
        probeRound.incrementAndGet()
        probes?.cancel(false)
        probes = null
    }

    /** Runs on [prober]: one bounded health check per remote, skipping the one we are connected to. */
    private fun probeRemotes(round: Int) {
        val connected = (EngineController.target as? EngineTarget.Remote)?.engine?.id
        val remotes = prefs.remotes
        health.keys.retainAll(remotes.map { it.id }.toSet())
        for (r in remotes) {
            if (round != probeRound.get()) return
            if (r.id == connected) continue
            val ok = try { RemoteHealth.isOk(Http.getWithin(StreamUrls.health(r.endpoint), PROBE_TIMEOUT_MS)) } catch (_: Exception) { false }
            health[r.id] = ok
        }
        host.onMain { if (round == probeRound.get()) renderList(EngineSelection.current(host)) }
    }

    private fun onStartStop() {
        when (EngineController.state) {
            EngineState.Running, EngineState.Starting -> EngineController.stop(host)
            EngineState.Stopping -> Unit
            else -> host.startEngine()
        }
    }

    /**
     * The local version the section describes: the selected one, or while a remote is selected the
     * one choosing this device again would run. Null when no version is chosen or installed.
     */
    private fun localVersion(): String? = when (val c = EngineSelection.current(host)) {
        is EngineChoice.Local -> c.version
        is EngineChoice.Remote -> (EngineChoices.local(prefs.lastLocalVersion, EngineFiles.store(host).installed()) as? EngineChoice.Local)?.version
        null -> null
    }

    fun render() {
        val choice = EngineSelection.current(host)
        renderList(choice)
        renderLocal(localVersion(), enabled = choice !is EngineChoice.Remote)
    }

    private fun renderList(selected: EngineChoice?) {
        val entries = listOf<Entry>(Entry.Local) + prefs.remotes.map { Entry.Remote(it) }
        list.syncRows(entries, { it.key }, { LayoutInflater.from(host).inflate(R.layout.item_engine, list, false) }) { row, e ->
            bindEntry(row, e, if (e is Entry.Remote) selected == EngineChoice.Remote(e.engine.id) else selected !is EngineChoice.Remote)
        }
    }

    private fun bindEntry(row: View, e: Entry, selected: Boolean) {
        val target = EngineController.target
        val live = when (e) {
            Entry.Local -> target is EngineTarget.Local
            is Entry.Remote -> (target as? EngineTarget.Remote)?.engine?.id == e.engine.id
        }
        val stateColor = when (EngineController.state) {
            EngineState.Running -> R.color.ok
            EngineState.Starting, EngineState.Stopping -> R.color.warn
            is EngineState.Crashed, is EngineState.Unreachable -> R.color.err
            EngineState.Stopped -> R.color.text_muted
        }
        val (color, status) = when {
            live -> stateColor to host.getString(
                when {
                    e is Entry.Local -> R.string.engine_meta_running
                    EngineController.state is EngineState.Unreachable -> R.string.engine_meta_offline
                    EngineController.state == EngineState.Running -> R.string.engine_meta_connected
                    else -> R.string.engine_meta_checking
                },
            )
            e is Entry.Remote -> when (health[e.engine.id]) {
                true -> R.color.ok to host.getString(R.string.engine_meta_online)
                false -> R.color.err to host.getString(R.string.engine_meta_offline)
                null -> R.color.text_muted to host.getString(R.string.engine_meta_checking)
            }
            else -> R.color.text_muted to null
        }
        @Suppress("DEPRECATION") // Context.getColor needs API 23; colors here do not depend on the theme.
        row.findViewById<ImageView>(R.id.dot).setColorFilter(host.resources.getColor(color))
        val meta = when (e) {
            Entry.Local -> {
                val version = localVersion()?.let { "v$it" } ?: host.getString(R.string.local_meta_none)
                host.joinMeta(listOf(version, status)) + "\n" + host.getString(R.string.local_meta_hint)
            }
            is Entry.Remote -> host.joinMeta(listOf(e.engine.endpoint.display, status))
        }
        row.findViewById<TextView>(R.id.title).text = when (e) {
            Entry.Local -> host.getString(R.string.engine_local_name)
            is Entry.Remote -> e.engine.name
        }
        row.findViewById<TextView>(R.id.meta).text = meta
        row.findViewById<View>(R.id.badge).visibility = if (selected) View.VISIBLE else View.GONE
        row.setOnClickListener {
            when (e) {
                Entry.Local -> if (!selected) { EngineSelection.selectLocal(host); host.renderCurrent() }
                is Entry.Remote -> showRemoteMenu(e.engine, selected)
            }
        }
    }

    private fun showRemoteMenu(engine: RemoteEngine, selected: Boolean) {
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (!selected) options += host.getString(R.string.engine_menu_use) to {
            EngineSelection.select(host, EngineChoice.Remote(engine.id))
            host.renderCurrent()
        }
        options += host.getString(R.string.engine_menu_edit) to {
            RemoteDialog.show(host, engine) {
                health.remove(engine.id)
                host.renderCurrent()
                onVisible()
            }
        }
        options += host.getString(R.string.engine_menu_remove) to {
            EngineSelection.removeRemote(host, engine.id)
            health.remove(engine.id)
            host.renderCurrent()
        }
        AlertDialog.Builder(host)
            .setTitle(engine.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Draws the Local engine section for [version] (null: none chosen yet). With [enabled] false,
     * a remote engine is selected and every control in the section is disabled.
     */
    private fun renderLocal(version: String?, enabled: Boolean) {
        localDisabled.visibility = if (enabled) View.GONE else View.VISIBLE
        if (enabled != localEnabled) {
            localEnabled = enabled
            setTreeEnabled(localSection, enabled)
            localSection.alpha = if (enabled) 1f else DISABLED_ALPHA
            localTitle.alpha = localSection.alpha
            // Re-enabling the tree also re-enabled rows another setting locks.
            if (enabled) {
                rows.applyLocks()
                proxy.refresh()
            }
        }
        val supported = version != null && EngineSettings.proxySupported(version)
        if (supported != proxySupported) {
            proxySupported = supported
            if (enabled) rows.applyLocks()
        }
        proxySection.visibility = if (supported) View.VISIBLE else View.GONE
        // Only this device's session describes the local engine; a remote session is another engine.
        val s = if (enabled) EngineController.state else EngineState.Stopped
        val shown = (if (enabled) EngineController.runningVersion else null) ?: version
        if (shown == null) {
            localState.setText(R.string.local_none)
            localDetail.setText(R.string.local_none_detail)
            localDetail.visibility = View.VISIBLE
        } else {
            localState.text = host.getString(
                R.string.engine_detail_named,
                host.getString(
                    when (s) {
                        EngineState.Starting -> R.string.state_starting
                        EngineState.Running -> R.string.state_running
                        EngineState.Stopping -> R.string.state_stopping
                        is EngineState.Crashed, is EngineState.Unreachable -> R.string.state_error
                        EngineState.Stopped -> R.string.state_stopped
                    },
                ),
                "v$shown",
            )
            localDetail.visibility = View.GONE
        }
        val active = s == EngineState.Running || s == EngineState.Starting || s == EngineState.Stopping
        startStop.text = host.getString(if (active) R.string.action_stop else R.string.action_start)
        startStop.isEnabled = enabled && shown != null && s != EngineState.Stopping
        reinstall.isEnabled = enabled && version != null && s != EngineState.Starting && s != EngineState.Stopping
        // The setting applies on the next start, which runs the selected version.
        identityRow.visibility = if (version != null && EngineSettings.supportsAcestreamIdentity(version)) View.VISIBLE else View.GONE
        val latest = prefs.latestVersion
        if (enabled && shown != null && latest != null && Releases.compareVersions(latest, shown) > 0) {
            update.visibility = View.VISIBLE
            update.text = host.getString(R.string.engine_update_available, latest)
        } else {
            update.visibility = View.GONE
        }
        castRow.visibility = if (shown != null && CastGate.supports(shown)) View.VISIBLE else View.GONE
        val running = EngineController.runningSettings
        restartBanner.visibility = if (enabled && s == EngineState.Running && running != null && running != prefs.settings) View.VISIBLE else View.GONE
    }

    /** Enables or disables [view] and everything inside it, so nothing in it takes clicks or focus. */
    private fun setTreeEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setTreeEnabled(view.getChildAt(i), enabled)
    }

    companion object {
        private const val PROBE_INTERVAL_MS = 15_000L
        private const val PROBE_TIMEOUT_MS = 2_000
        private const val DISABLED_ALPHA = 0.5f

        /** One daemon thread for list health checks, idle while Settings is hidden. */
        private val prober = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-probe").apply { isDaemon = true } }
    }
}
