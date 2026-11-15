package io.github.outpacestream.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineSelection

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // A remote engine connects when the app opens; at boot there is nothing to follow yet.
        if (EngineSelection.current(context) is EngineChoice.Remote) return
        if (!Prefs(context).startOnBoot || EngineSelection.localVersion(context) == null) return
        EngineController.start(context)
    }
}
