package io.github.outpacestream.android.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.EngineSettings
import java.util.Locale

/**
 * Builds settings rows bound to preferences. Every change saves at once and then calls [onChange].
 * [refresh] redraws every row from the stored values.
 */
class SettingRows(private val host: MainActivity, private val onChange: () -> Unit) {
    private val prefs: Prefs get() = host.prefs

    /** A switch row bound to a preference: how to read it and how to store a new value. */
    private class Toggle(val row: View, val read: () -> Boolean, val write: (Boolean) -> Unit) {
        val switch: Switch = row.findViewById(R.id.toggle)
    }

    private class PortField(val field: EditText, val get: (EngineSettings) -> Int)

    private val toggles = ArrayList<Toggle>()
    private val choices = ArrayList<() -> Unit>()
    private val ports = ArrayList<PortField>()

    fun row(group: LinearLayout, title: Int, summary: Int, index: Int = -1): View {
        val row = LayoutInflater.from(host).inflate(R.layout.item_setting, group, false)
        row.findViewById<TextView>(R.id.title).setText(title)
        row.findViewById<TextView>(R.id.summary).apply { setText(summary); visibility = View.VISIBLE }
        group.addView(row, index)
        return row
    }

    fun toggle(group: LinearLayout, title: Int, summary: Int, read: () -> Boolean, index: Int = -1, write: (Boolean) -> Unit): View {
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
            onChange()
        }
        toggles += t
        return t.row
    }

    fun engineToggle(
        group: LinearLayout,
        title: Int,
        summary: Int,
        get: (EngineSettings) -> Boolean,
        index: Int = -1,
        set: (EngineSettings, Boolean) -> EngineSettings,
    ): View = toggle(group, title, summary, { get(prefs.settings) }, index) { v -> prefs.settings = set(prefs.settings, v) }

    /** A row whose summary names the current option; clicking it opens a single-choice dialog. */
    fun <T> engineChoice(
        group: LinearLayout,
        title: Int,
        summary: Int,
        options: List<Pair<T, Int>>,
        get: (EngineSettings) -> T,
        set: (EngineSettings, T) -> EngineSettings,
    ) {
        val row = row(group, title, summary)
        val summaryView = row.findViewById<TextView>(R.id.summary)
        val refresh = {
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
                    refresh()
                    onChange()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        choices += refresh
        refresh()
    }

    /** Adds a numeric port row to [group] and binds it; returns its field. */
    fun portRow(group: LinearLayout, title: Int, summary: Int, get: (EngineSettings) -> Int, set: (EngineSettings, Int) -> EngineSettings): EditText {
        val row = LayoutInflater.from(host).inflate(R.layout.item_setting_number, group, false)
        row.findViewById<TextView>(R.id.title).setText(title)
        row.findViewById<TextView>(R.id.summary).setText(summary)
        group.addView(row)
        return row.findViewById<EditText>(R.id.number).also { portField(it, get, set) }
    }

    fun portField(field: EditText, get: (EngineSettings) -> Int, set: (EngineSettings, Int) -> EngineSettings) {
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { savePort(field, get, set); field.hideKeyboard(); true } else false
        }
        field.setOnFocusChangeListener { _, focused -> if (!focused) savePort(field, get, set) }
        ports += PortField(field, get)
    }

    fun refresh() {
        for (t in toggles) t.switch.isChecked = t.read()
        for (c in choices) c()
        for (p in ports) {
            p.field.setText(String.format(Locale.ROOT, "%d", p.get(prefs.settings)))
            p.field.error = null
        }
    }

    private fun savePort(field: EditText, get: (EngineSettings) -> Int, set: (EngineSettings, Int) -> EngineSettings) {
        val value = field.text.toString().toIntOrNull()
        if (value == null || !EngineSettings.validPort(value)) {
            field.error = host.getString(R.string.settings_port_invalid)
            return
        }
        val next = set(prefs.settings, value)
        if (!EngineSettings.validPorts(next.port, next.peerPort)) {
            field.error = host.getString(R.string.settings_port_conflict)
            return
        }
        field.error = null
        if (value != get(prefs.settings)) {
            prefs.settings = next
            onChange()
        }
    }
}
