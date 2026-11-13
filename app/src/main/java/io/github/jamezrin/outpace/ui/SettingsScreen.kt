package io.github.jamezrin.outpace.ui

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import io.github.jamezrin.outpace.BuildConfig
import io.github.jamezrin.outpace.R
import io.github.jamezrin.outpace.core.EngineSettings
import io.github.jamezrin.outpace.core.Releases
import io.github.jamezrin.outpace.engine.EngineController
import io.github.jamezrin.outpace.engine.EngineState

/** Engine and app settings. Every change saves at once; engine settings apply on the next start. */
class SettingsScreen(host: MainActivity, parent: ViewGroup) : Screen(host, parent, R.layout.screen_settings) {
    private val port = find<EditText>(R.id.port)
    private val restartBanner = find<View>(R.id.restart_banner)
    private val battery = find<Button>(R.id.battery)
    private val batterySummary = find<View>(R.id.battery_summary)
    private val engineSwitches: Map<Int, Pair<(EngineSettings) -> Boolean, (EngineSettings, Boolean) -> EngineSettings>> = mapOf(
        R.id.search to Pair({ s: EngineSettings -> s.search }, { s: EngineSettings, v: Boolean -> s.copy(search = v) }),
        R.id.lan to Pair({ s: EngineSettings -> s.lan }, { s: EngineSettings, v: Boolean -> s.copy(lan = v) }),
        R.id.seeding to Pair({ s: EngineSettings -> s.seeding }, { s: EngineSettings, v: Boolean -> s.copy(seeding = v) }),
        R.id.inbound to Pair({ s: EngineSettings -> s.inbound }, { s: EngineSettings, v: Boolean -> s.copy(inbound = v) }),
        R.id.port_mapping to Pair({ s: EngineSettings -> s.portMapping }, { s: EngineSettings, v: Boolean -> s.copy(portMapping = v) }),
        R.id.ace_compat to Pair({ s: EngineSettings -> s.aceCompat }, { s: EngineSettings, v: Boolean -> s.copy(aceCompat = v) }),
    )

    /** Set while switches are filled from preferences, so that filling them saves nothing. */
    private var binding = false

    init {
        for ((id, access) in engineSwitches) {
            find<Switch>(id).setOnCheckedChangeListener { _, checked ->
                if (!binding) save(access.second(prefs.settings, checked))
            }
        }
        find<Switch>(R.id.boot).setOnCheckedChangeListener { _, checked -> if (!binding) prefs.startOnBoot = checked }
        find<Switch>(R.id.idle).setOnCheckedChangeListener { _, checked -> if (!binding) prefs.idleStop = checked }
        port.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { savePort(); port.hideKeyboard(); true } else false
        }
        port.setOnFocusChangeListener { _, focused -> if (!focused) savePort() }
        battery.setOnClickListener { BatteryPrompt.request(host) }
        find<Button>(R.id.restart).setOnClickListener { EngineController.restart(host) }
        find<TextView>(R.id.about).text = host.getString(R.string.settings_about, BuildConfig.VERSION_NAME, Releases.REPO)
    }

    override fun focusTarget(): View = find(R.id.search)

    override fun onShown() {
        binding = true
        val s = prefs.settings
        for ((id, access) in engineSwitches) find<Switch>(id).isChecked = access.first(s)
        find<Switch>(R.id.boot).isChecked = prefs.startOnBoot
        find<Switch>(R.id.idle).isChecked = prefs.idleStop
        port.setText(String.format(java.util.Locale.ROOT, "%d", s.port))
        port.error = null
        binding = false
    }

    override fun render() {
        val running = EngineController.runningSettings
        val changed = EngineController.state == EngineState.Running && running != null && running != prefs.settings
        restartBanner.visibility = if (changed) View.VISIBLE else View.GONE
        val exempt = BatteryPrompt.isExempt(host)
        battery.visibility = if (exempt) View.GONE else View.VISIBLE
        batterySummary.visibility = battery.visibility
    }

    private fun save(s: EngineSettings) {
        prefs.settings = s
        render()
    }

    private fun savePort() {
        if (binding) return
        val value = port.text.toString().toIntOrNull()
        if (value == null || !EngineSettings.validPort(value)) {
            port.error = host.getString(R.string.settings_port_invalid)
            return
        }
        port.error = null
        if (value != prefs.settings.port) save(prefs.settings.copy(port = value))
    }
}
