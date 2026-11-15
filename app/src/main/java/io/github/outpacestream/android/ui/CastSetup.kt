package io.github.outpacestream.android.ui

import android.app.Activity
import android.app.AlertDialog
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R

/**
 * Turning casting on for this device's engine. A TV fetches the stream from the engine, so casting
 * needs LAN access; it is offered with the usual LAN warning and never turned on silently.
 */
object CastSetup {
    /** Saves casting on, then asks about LAN access when it is off. [done] runs once the settings are final. */
    fun enable(activity: Activity, done: () -> Unit) {
        val prefs = Prefs(activity)
        prefs.settings = prefs.settings.copy(cast = true)
        if (prefs.settings.lan) done() else confirmLan(activity, done)
    }

    /** Explains why casting needs LAN access and offers to turn it on; [done] runs either way. */
    fun confirmLan(activity: Activity, done: () -> Unit) {
        val message = activity.getString(R.string.cast_lan_text) + "\n\n" + activity.getString(R.string.settings_lan_warning)
        AlertDialog.Builder(activity)
            .setTitle(R.string.cast_lan_title)
            .setMessage(message)
            .setPositiveButton(R.string.cast_lan_turn_on) { _, _ ->
                val prefs = Prefs(activity)
                prefs.settings = prefs.settings.copy(lan = true)
                done()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> done() }
            .setOnCancelListener { done() }
            .show()
    }
}
