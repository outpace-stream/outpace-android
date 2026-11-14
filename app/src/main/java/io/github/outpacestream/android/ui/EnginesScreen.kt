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
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.EngineChoice
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
import io.github.outpacestream.android.engine.EngineTarget
import io.github.outpacestream.android.net.Http
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Every engine the app can use, local versions and remote engines in one list, plus the controls
 * and settings of the selected one. Remote engines are health-checked only while this tab shows.
 */
class EnginesScreen(host: MainActivity, parent: ViewGroup) : Screen(host, parent, R.layout.screen_engines) {
    private sealed interface Entry {
        val key: String
        val choice: EngineChoice

        data class Local(val version: String) : Entry {
            override val key get() = "local:$version"
            override val choice get() = EngineChoice.Local(version)
        }

        data class Remote(val engine: RemoteEngine) : Entry {
            override val key get() = "remote:${engine.id}"
            override val choice get() = EngineChoice.Remote(engine.id)
        }
    }

    private val list = find<LinearLayout>(R.id.engine_list)
    private val addButton = find<Button>(R.id.add_engine)
    private val localSection = find<View>(R.id.local_section)
    private val localState = find<TextView>(R.id.local_state)
    private val startStop = find<Button>(R.id.start_stop)
    private val restart = find<Button>(R.id.restart)
    private val update = find<Button>(R.id.update)
    private val restartBanner = find<View>(R.id.restart_banner)
    private val remoteSection = find<View>(R.id.remote_section)
    private val remoteState = find<TextView>(R.id.remote_state)
    private val connect = find<Button>(R.id.connect)
    private val rows = SettingRows(host) { render() }

    /** Remote id to whether its last probe answered; absent until probed. */
    private val health = ConcurrentHashMap<String, Boolean>()
    private var probes: ScheduledFuture<*>? = null

    init {
        addButton.setOnClickListener { showAddMenu() }
        startStop.setOnClickListener { onStartStop() }
        restart.setOnClickListener { EngineController.restart(host) }
        find<Button>(R.id.restart_apply).setOnClickListener { EngineController.restart(host) }
        find<Button>(R.id.versions).setOnClickListener { host.open(VersionsActivity::class.java) }
        update.setOnClickListener { host.open(VersionsActivity::class.java) }
        find<Button>(R.id.logs).setOnClickListener { host.open(LogsActivity::class.java) }
        connect.setOnClickListener {
            if (sessionActive()) EngineController.stop(host) else host.startEngine()
        }
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
        rows.engineToggle(peers, R.string.settings_inbound, R.string.settings_inbound_summary, { it.inbound }) { s, v -> s.copy(inbound = v) }
        rows.engineToggle(peers, R.string.settings_port_mapping, R.string.settings_port_mapping_summary, { it.portMapping }) { s, v ->
            s.copy(portMapping = v)
        }
        rows.portRow(peers, R.string.settings_peer_port, R.string.settings_peer_port_summary, { it.peerPort }) { s, v -> s.copy(peerPort = v) }
        rows.engineToggle(peers, R.string.settings_ace_compat, R.string.settings_ace_compat_summary, { it.aceCompat }) { s, v -> s.copy(aceCompat = v) }
    }

    override fun focusTarget(): View = list.getChildAt(0) ?: addButton

    override fun onShown() = rows.refresh()

    override fun onVisible() {
        probes?.cancel(false)
        probes = prober.scheduleWithFixedDelay({ probeRemotes() }, 0, PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    override fun onInvisible() {
        probes?.cancel(false)
        probes = null
    }

    /** Runs on [prober]: one bounded health check per remote, skipping the one we are connected to. */
    private fun probeRemotes() {
        val connected = (EngineController.target as? EngineTarget.Remote)?.engine?.id
        val remotes = prefs.remotes
        health.keys.retainAll(remotes.map { it.id }.toSet())
        for (r in remotes) {
            if (r.id == connected) continue
            val ok = try { RemoteHealth.isOk(Http.getWithin(StreamUrls.health(r.endpoint), PROBE_TIMEOUT_MS)) } catch (_: Exception) { false }
            health[r.id] = ok
        }
        host.onMain { renderList() }
    }

    private fun sessionActive(): Boolean {
        val s = EngineController.state
        return s != EngineState.Stopped && s !is EngineState.Crashed
    }

    private fun onStartStop() {
        when (EngineController.state) {
            EngineState.Running, EngineState.Starting -> EngineController.stop(host)
            EngineState.Stopping -> Unit
            else -> host.startEngine()
        }
    }

    override fun render() {
        renderList()
        when (val c = EngineSelection.current(host)) {
            is EngineChoice.Local -> {
                localSection.visibility = View.VISIBLE
                remoteSection.visibility = View.GONE
                renderLocal(c.version)
            }
            is EngineChoice.Remote -> {
                localSection.visibility = View.GONE
                remoteSection.visibility = View.VISIBLE
                EngineSelection.remote(host)?.let(::renderRemote)
            }
            null -> {
                localSection.visibility = View.GONE
                remoteSection.visibility = View.GONE
            }
        }
    }

    private fun entries(): List<Entry> =
        EngineFiles.store(host).installed().map { Entry.Local(it) } + prefs.remotes.map { Entry.Remote(it) }

    private fun renderList() {
        val selected = EngineSelection.current(host)
        list.syncRows(entries(), { it.key }, { LayoutInflater.from(host).inflate(R.layout.item_engine, list, false) }) { row, e ->
            bindEntry(row, e, e.choice == selected)
        }
    }

    private fun bindEntry(row: View, e: Entry, selected: Boolean) {
        val target = EngineController.target
        val live = when (e) {
            is Entry.Local -> (target as? EngineTarget.Local)?.version == e.version
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
        val (title, detail) = when (e) {
            is Entry.Local -> host.getString(R.string.engine_local_name) to "v${e.version}"
            is Entry.Remote -> e.engine.name to e.engine.endpoint.display
        }
        row.findViewById<TextView>(R.id.title).text = title
        row.findViewById<TextView>(R.id.meta).text = host.joinMeta(listOf(detail, status))
        row.findViewById<View>(R.id.badge).visibility = if (selected) View.VISIBLE else View.GONE
        row.setOnClickListener { showEntryMenu(e, selected) }
    }

    private fun showEntryMenu(e: Entry, selected: Boolean) {
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (!selected) options += host.getString(R.string.engine_menu_use) to { EngineSelection.select(host, e.choice); render() }
        if (e is Entry.Remote) options += host.getString(R.string.engine_menu_edit) to { RemoteDialog.show(host, e.engine) { render() } }
        options += host.getString(R.string.engine_menu_remove) to {
            when (e) {
                is Entry.Local -> if (!EngineSelection.removeLocal(host, e.version)) host.toast(host.getString(R.string.version_remove_running))
                is Entry.Remote -> {
                    EngineSelection.removeRemote(host, e.engine.id)
                    health.remove(e.engine.id)
                }
            }
            render()
        }
        val title = when (e) {
            is Entry.Local -> "${host.getString(R.string.engine_local_name)}, v${e.version}"
            is Entry.Remote -> e.engine.name
        }
        AlertDialog.Builder(host)
            .setTitle(title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddMenu() {
        val options = listOf(
            host.getString(R.string.add_engine_download) to { host.open(VersionsActivity::class.java) },
            host.getString(R.string.add_engine_remote) to { RemoteDialog.show(host, null) { render(); onVisible() } },
        )
        AlertDialog.Builder(host)
            .setTitle(R.string.action_add_engine)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderLocal(version: String) {
        val s = EngineController.state
        val shown = EngineController.runningVersion ?: version
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
        startStop.text = host.getString(
            if (s == EngineState.Running || s == EngineState.Starting || s == EngineState.Stopping) R.string.action_stop else R.string.action_start,
        )
        startStop.isEnabled = s != EngineState.Stopping
        restart.isEnabled = s == EngineState.Running
        val latest = prefs.latestVersion
        if (latest != null && Releases.compareVersions(latest, shown) > 0) {
            update.visibility = View.VISIBLE
            update.text = host.getString(R.string.engine_update_available, latest)
        } else {
            update.visibility = View.GONE
        }
        val running = EngineController.runningSettings
        restartBanner.visibility = if (s == EngineState.Running && running != null && running != prefs.settings) View.VISIBLE else View.GONE
    }

    private fun renderRemote(engine: RemoteEngine) {
        val s = EngineController.state
        remoteState.text = when (s) {
            EngineState.Starting -> host.getString(R.string.remote_state_connecting, engine.name)
            EngineState.Running -> host.getString(R.string.remote_state_connected, engine.name)
            is EngineState.Unreachable -> host.getString(R.string.remote_state_unreachable, engine.name, s.reason)
            else -> host.getString(R.string.remote_state_disconnected, engine.name)
        }
        connect.text = host.getString(if (sessionActive()) R.string.action_disconnect else R.string.action_connect)
        connect.isEnabled = s != EngineState.Stopping
    }

    companion object {
        private const val PROBE_INTERVAL_MS = 15_000L
        private const val PROBE_TIMEOUT_MS = 2_000

        /** One daemon thread for list health checks, idle while the Engines tab is hidden. */
        private val prober = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-probe").apply { isDaemon = true } }
    }
}
