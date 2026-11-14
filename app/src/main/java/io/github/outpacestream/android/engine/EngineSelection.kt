package io.github.outpacestream.android.engine

import android.content.Context
import io.github.outpacestream.android.EngineFiles
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.EngineChoices
import io.github.outpacestream.android.core.RemoteEngine
import io.github.outpacestream.android.core.RemoteEngines

/**
 * The one place that reads and changes which engine the app uses, and keeps the running engine or
 * remote connection consistent with it.
 */
object EngineSelection {
    /** The selected engine, or null. An install from before selection existed is migrated once. */
    fun current(context: Context): EngineChoice? {
        val prefs = Prefs(context)
        val installed = EngineFiles.store(context).installed()
        val raw = prefs.engineChoiceRaw ?: EngineChoices.encode(EngineChoices.migrate(prefs.legacyActiveVersion, installed)).also {
            prefs.engineChoiceRaw = it
        }
        return EngineChoices.resolve(EngineChoices.decode(raw), installed, prefs.remotes)
    }

    /** The selected local version, or null when a remote or nothing is selected. */
    fun localVersion(context: Context): String? = (current(context) as? EngineChoice.Local)?.version

    fun remote(context: Context): RemoteEngine? {
        val c = current(context) as? EngineChoice.Remote ?: return null
        return Prefs(context).remotes.firstOrNull { it.id == c.id }
    }

    /** Makes [choice] the selection, stopping or restarting what runs so it matches. */
    fun select(context: Context, choice: EngineChoice?) {
        val before = current(context)
        val prefs = Prefs(context)
        prefs.engineChoiceRaw = EngineChoices.encode(choice)
        if (choice == before) return
        if (before is EngineChoice.Remote) prefs.remoteConnected = false
        val s = EngineController.state
        val active = s != EngineState.Stopped && s !is EngineState.Crashed
        if (!active) return
        when {
            choice is EngineChoice.Local && before is EngineChoice.Local -> {
                if (EngineController.runningVersion != choice.version) EngineController.restart(context)
            }
            else -> EngineController.stop(context)
        }
    }

    /** A finished download; it becomes the selection only when nothing is selected. */
    fun installed(context: Context, version: String) {
        val next = EngineChoices.afterInstall(current(context), version)
        Prefs(context).engineChoiceRaw = EngineChoices.encode(next)
    }

    /** Deletes a downloaded version; false (and nothing removed) while that version runs. */
    fun removeLocal(context: Context, version: String): Boolean {
        if (version == EngineController.runningVersion) return false
        EngineFiles.store(context).remove(version)
        val next = EngineChoices.afterRemove(current(context), EngineChoice.Local(version))
        Prefs(context).engineChoiceRaw = EngineChoices.encode(next)
        return true
    }

    /** Forgets a remote engine, disconnecting first when it is the selected one. */
    fun removeRemote(context: Context, id: String) {
        val removed = EngineChoice.Remote(id)
        if (current(context) == removed) select(context, null)
        val prefs = Prefs(context)
        prefs.remotes = prefs.remotes.filter { it.id != id }
    }

    /** Adds [r] or replaces the remote with the same id; false when the list is full. */
    fun saveRemote(context: Context, r: RemoteEngine): Boolean {
        val prefs = Prefs(context)
        val list = prefs.remotes
        val i = list.indexOfFirst { it.id == r.id }
        if (i < 0 && list.size >= RemoteEngines.MAX_REMOTES) return false
        prefs.remotes = if (i < 0) list + r else list.toMutableList().also { it[i] = r }
        return true
    }
}
