package io.github.outpacestream.android.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.ProxyMode
import io.github.outpacestream.android.core.SocksProxy

/**
 * The outbound SOCKS5 proxy rows of the Local engine settings: when to use it and which proxy. A mode other
 * than Off needs a proxy, so picking one without a proxy asks for it first. [onChange] runs after
 * every saved change. The password is only ever read into the dialog's masked field.
 */
class ProxySettings(private val host: MainActivity, group: LinearLayout, private val onChange: () -> Unit) {
    private val prefs: Prefs get() = host.prefs
    private val modes = listOf(
        ProxyMode.OFF to R.string.settings_proxy_off,
        ProxyMode.ALWAYS to R.string.settings_proxy_always,
        ProxyMode.FALLBACK to R.string.settings_proxy_fallback,
    )

    private val modeRow = inflateRow(group, R.string.settings_proxy)
    private val serverRow = inflateRow(group, R.string.settings_proxy_server)
    private val modeSummary = modeRow.findViewById<TextView>(R.id.summary)
    private val serverSummary = serverRow.findViewById<TextView>(R.id.summary)

    /** RFC 1929 sends the password in cleartext, so a remote proxy with credentials gets a warning. */
    private val cleartextNote = TextView(host, null, 0, R.style.Text_Muted).apply {
        setText(R.string.settings_proxy_cleartext)
        @Suppress("DEPRECATION") // Context.getColor needs API 23; this color does not depend on the theme.
        setTextColor(host.resources.getColor(R.color.warn))
        val pad = (16 * host.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        group.addView(this)
    }

    init {
        modeRow.setOnClickListener { pickMode() }
        serverRow.setOnClickListener { editServer(null) }
        refresh()
    }

    private fun inflateRow(group: LinearLayout, title: Int): View {
        val row = LayoutInflater.from(host).inflate(R.layout.item_setting, group, false)
        row.findViewById<TextView>(R.id.title).setText(title)
        row.findViewById<TextView>(R.id.summary).visibility = View.VISIBLE
        group.addView(row)
        return row
    }

    fun refresh() {
        val s = prefs.settings
        val proxy = s.proxy
        val mode = if (proxy == null) ProxyMode.OFF else s.proxyMode
        modeSummary.text = host.getString(R.string.settings_proxy_summary, host.getString(modes.first { it.first == mode }.second))
        serverSummary.text = when {
            proxy == null -> host.getString(R.string.settings_proxy_server_none)
            proxy.hasCredentials -> host.getString(R.string.settings_proxy_server_auth, proxy.address)
            else -> proxy.address
        }
        cleartextNote.visibility = if (proxy != null && proxy.hasCredentials && !proxy.isLoopback) View.VISIBLE else View.GONE
    }

    private fun pickMode() {
        val s = prefs.settings
        val current = if (s.proxy == null) 0 else modes.indexOfFirst { it.first == s.proxyMode }.coerceAtLeast(0)
        AlertDialog.Builder(host)
            .setTitle(R.string.settings_proxy)
            .setSingleChoiceItems(modes.map { host.getString(it.second) }.toTypedArray<CharSequence>(), current) { dialog, which ->
                dialog.dismiss()
                val mode = modes[which].first
                if (mode != ProxyMode.OFF && prefs.settings.proxy == null) editServer(mode) else save(prefs.settings.copy(proxyMode = mode))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Edits the proxy; [thenMode] is the mode to turn on once a proxy is saved. Clearing the address removes the proxy and turns it off. */
    private fun editServer(thenMode: ProxyMode?) {
        val view = LayoutInflater.from(host).inflate(R.layout.dialog_proxy, null)
        val address = view.findViewById<EditText>(R.id.proxy_address)
        val username = view.findViewById<EditText>(R.id.proxy_username)
        val password = view.findViewById<EditText>(R.id.proxy_password)
        prefs.settings.proxy?.let {
            address.setText(it.address)
            username.setText(it.username)
            password.setText(it.password)
        }
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.settings_proxy_server)
            .setView(view)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = address.text.toString().trim()
                val u = username.text.toString()
                val p = password.text.toString()
                address.error = null
                username.error = null
                if (a.isEmpty() && thenMode == null) {
                    dialog.dismiss()
                    save(prefs.settings.copy(proxy = null, proxyMode = ProxyMode.OFF))
                    return@setOnClickListener
                }
                when (SocksProxy.validate(a, u, p)) {
                    SocksProxy.Problem.ADDRESS -> address.error = host.getString(R.string.proxy_address_invalid)
                    SocksProxy.Problem.CREDENTIALS_INCOMPLETE -> username.error = host.getString(R.string.proxy_credentials_incomplete)
                    SocksProxy.Problem.CREDENTIALS_TOO_LONG -> username.error = host.getString(R.string.proxy_credentials_too_long)
                    null -> {
                        val proxy = SocksProxy.of(a, u, p) ?: return@setOnClickListener
                        dialog.dismiss()
                        val s = prefs.settings.copy(proxy = proxy)
                        save(if (thenMode != null) s.copy(proxyMode = thenMode) else s)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun save(next: EngineSettings) {
        if (next == prefs.settings) return
        prefs.settings = next
        refresh()
        onChange()
    }
}
