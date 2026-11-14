package io.github.outpacestream.android.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.EditText
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.RemoteEngine
import io.github.outpacestream.android.core.RemoteEngines
import io.github.outpacestream.android.engine.EngineSelection

/** Adds a remote engine, or edits [existing]. Invalid input keeps the dialog open with the error. */
object RemoteDialog {
    fun show(host: MainActivity, existing: RemoteEngine?, onSaved: () -> Unit) {
        val view = LayoutInflater.from(host).inflate(R.layout.dialog_remote, null)
        val name = view.findViewById<EditText>(R.id.remote_name)
        val address = view.findViewById<EditText>(R.id.remote_address)
        if (existing != null) {
            name.setText(existing.name)
            address.setText(existing.endpoint.display)
        }
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.remote_add_title)
            .setView(view)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val endpoint = RemoteEngines.parseAddress(address.text.toString())
                name.error = if (RemoteEngines.validName(n)) null else host.getString(R.string.remote_name_invalid)
                address.error = if (endpoint != null) null else host.getString(R.string.remote_address_invalid)
                if (name.error != null || endpoint == null) return@setOnClickListener
                val saved = RemoteEngine(existing?.id ?: RemoteEngines.newId(), n, endpoint)
                if (!EngineSelection.saveRemote(host, saved)) {
                    host.toast(host.getString(R.string.remote_full, RemoteEngines.MAX_REMOTES))
                    return@setOnClickListener
                }
                dialog.dismiss()
                onSaved()
            }
        }
        dialog.show()
    }
}
