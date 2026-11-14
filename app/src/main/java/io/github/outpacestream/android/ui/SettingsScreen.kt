package io.github.outpacestream.android.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.outpacestream.android.BuildConfig
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.HlsStart
import io.github.outpacestream.android.core.Releases
import io.github.outpacestream.android.core.StartupBuffer
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineState
import java.util.Locale

/** Engine and app settings. Every change saves at once; engine settings apply on the next start. */
class SettingsScreen(host: MainActivity, parent: ViewGroup) : Screen(host, parent, R.layout.screen_settings) {
    private val port = find<EditText>(R.id.port)
    private val restartBanner = find<View>(R.id.restart_banner)

    /** A switch row bound to a preference: how to read it and how to store a new value. */
    private class Toggle(val row: View, val read: () -> Boolean, val write: (Boolean) -> Unit) {
        val switch: Switch = row.findViewById(R.id.toggle)
    }

    /** A row that picks one preset from a dialog; [refresh] redraws its current value. */
    private class Choice(val refresh: () -> Unit)

    private val toggles = ArrayList<Toggle>()
    private val choices = ArrayList<Choice>()
    private lateinit var batteryRow: View
    private lateinit var searchRow: View

    init {
        val search = find<LinearLayout>(R.id.group_search)
        val playback = find<LinearLayout>(R.id.group_playback)
        val network = find<LinearLayout>(R.id.group_network)
        val peers = find<LinearLayout>(R.id.group_peers)
        val background = find<LinearLayout>(R.id.group_background)

        searchRow = engineToggle(search, R.string.settings_search, R.string.settings_search_summary, { it.search }) { s, v -> s.copy(search = v) }
        engineChoice(
            playback, R.string.settings_startup_buffer, R.string.settings_startup_buffer_summary,
            listOf(
                StartupBuffer.SMOOTH to R.string.settings_startup_buffer_smooth,
                StartupBuffer.BALANCED to R.string.settings_startup_buffer_balanced,
                StartupBuffer.FAST to R.string.settings_startup_buffer_fast,
                StartupBuffer.OFF to R.string.settings_startup_buffer_off,
            ),
            { it.startupBuffer },
        ) { s, v -> s.copy(startupBuffer = v) }
        engineChoice(
            playback, R.string.settings_hls_start, R.string.settings_hls_start_summary,
            listOf(HlsStart.DEFAULT to R.string.settings_hls_start_default, HlsStart.FAST to R.string.settings_hls_start_fast),
            { it.hlsStart },
        ) { s, v -> s.copy(hlsStart = v) }
        engineToggle(playback, R.string.settings_catalog_fallback, R.string.settings_catalog_fallback_summary, { it.catalogFallback }) { s, v ->
            s.copy(catalogFallback = v)
        }
        // LAN access goes above the port, which is already in the layout.
        engineToggle(network, R.string.settings_lan, R.string.settings_lan_warning, { it.lan }, index = 0) { s, v -> s.copy(lan = v) }
        engineToggle(peers, R.string.settings_seeding, R.string.settings_seeding_summary, { it.seeding }) { s, v -> s.copy(seeding = v) }
        engineToggle(peers, R.string.settings_inbound, R.string.settings_inbound_summary, { it.inbound }) { s, v -> s.copy(inbound = v) }
        engineToggle(peers, R.string.settings_port_mapping, R.string.settings_port_mapping_summary, { it.portMapping }) { s, v -> s.copy(portMapping = v) }
        engineToggle(peers, R.string.settings_ace_compat, R.string.settings_ace_compat_summary, { it.aceCompat }) { s, v -> s.copy(aceCompat = v) }
        toggle(background, R.string.settings_boot, R.string.settings_boot_summary, { prefs.startOnBoot }) { prefs.startOnBoot = it }
        toggle(background, R.string.settings_idle, R.string.settings_idle_summary, { prefs.idleStop }) { prefs.idleStop = it }
        batteryRow = row(background, R.string.settings_battery, R.string.settings_battery_summary)
        batteryRow.setOnClickListener { BatteryPrompt.request(host) }

        port.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { savePort(); port.hideKeyboard(); true } else false
        }
        port.setOnFocusChangeListener { _, focused -> if (!focused) savePort() }
        find<Button>(R.id.restart).setOnClickListener { EngineController.restart(host) }
        find<TextView>(R.id.about).text = host.getString(R.string.settings_about, BuildConfig.VERSION_NAME, Releases.REPO)
    }

    override fun focusTarget(): View = searchRow

    private fun row(group: LinearLayout, title: Int, summary: Int, index: Int = -1): View {
        val row = LayoutInflater.from(host).inflate(R.layout.item_setting, group, false)
        row.findViewById<TextView>(R.id.title).setText(title)
        row.findViewById<TextView>(R.id.summary).apply { setText(summary); visibility = View.VISIBLE }
        group.addView(row, index)
        return row
    }

    private fun toggle(group: LinearLayout, title: Int, summary: Int, read: () -> Boolean, index: Int = -1, write: (Boolean) -> Unit): View {
        val t = Toggle(row(group, title, summary, index), read, write)
        t.switch.visibility = View.VISIBLE
        // The switch is hidden from accessibility, so the row reports itself as the switch, reading
        // its state live so it never goes stale.
        t.row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = t.switch.isChecked
            }
        }
        t.row.setOnClickListener {
            val value = !t.switch.isChecked
            t.switch.isChecked = value
            t.write(value)
            render()
        }
        toggles += t
        return t.row
    }

    private fun engineToggle(
        group: LinearLayout,
        title: Int,
        summary: Int,
        get: (EngineSettings) -> Boolean,
        index: Int = -1,
        set: (EngineSettings, Boolean) -> EngineSettings,
    ): View = toggle(group, title, summary, { get(prefs.settings) }, index) { v -> prefs.settings = set(prefs.settings, v) }

    /** A row whose summary names the current option; clicking it opens a single-choice dialog. */
    private fun <T> engineChoice(
        group: LinearLayout,
        title: Int,
        summary: Int,
        options: List<Pair<T, Int>>,
        get: (EngineSettings) -> T,
        set: (EngineSettings, T) -> EngineSettings,
    ) {
        val row = row(group, title, summary)
        val summaryView = row.findViewById<TextView>(R.id.summary)
        val choice = Choice {
            val label = options.firstOrNull { it.first == get(prefs.settings) }?.second ?: options.first().second
            summaryView.text = host.getString(summary, host.getString(label))
        }
        row.setOnClickListener {
            val current = options.indexOfFirst { it.first == get(prefs.settings) }.coerceAtLeast(0)
            val labels = options.map { host.getString(it.second) }.toTypedArray<CharSequence>()
            AlertDialog.Builder(host)
                .setTitle(title)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    prefs.settings = set(prefs.settings, options[which].first)
                    choice.refresh()
                    render()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        choices += choice
        choice.refresh()
    }

    override fun onShown() {
        for (t in toggles) t.switch.isChecked = t.read()
        for (c in choices) c.refresh()
        port.setText(String.format(Locale.ROOT, "%d", prefs.settings.port))
        port.error = null
    }

    override fun render() {
        val running = EngineController.runningSettings
        val changed = EngineController.state == EngineState.Running && running != null && running != prefs.settings
        restartBanner.visibility = if (changed) View.VISIBLE else View.GONE
        batteryRow.visibility = if (BatteryPrompt.isExempt(host)) View.GONE else View.VISIBLE
    }

    private fun savePort() {
        val value = port.text.toString().toIntOrNull()
        if (value == null || !EngineSettings.validPort(value)) {
            port.error = host.getString(R.string.settings_port_invalid)
            return
        }
        port.error = null
        if (value != prefs.settings.port) {
            prefs.settings = prefs.settings.copy(port = value)
            render()
        }
    }
}
