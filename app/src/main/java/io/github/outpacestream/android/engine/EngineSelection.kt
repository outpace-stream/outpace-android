package io.github.outpacestream.android.engine

import android.content.Context
import io.github.outpacestream.android.EngineFiles
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.EngineChoices
import io.github.outpacestream.android.core.EngineTarget
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
        return EngineChoices.resolve(EngineChoices.decode(raw), installed, prefs.remotes).also(EngineController::selectionObserved)
    }

    /** Every selection write goes through here, so the controller never shows a stale session as the new one. */
    private fun store(context: Context, choice: EngineChoice?) {
        val prefs = Prefs(context)
        prefs.engineChoiceRaw = EngineChoices.encode(choice)
        if (choice is EngineChoice.Local) prefs.lastLocalVersion = choice.version
        EngineController.selectionChanged(choice)
    }

    /** The selected local version, or null when a remote or nothing is selected. */
    fun localVersion(context: Context): String? = (current(context) as? EngineChoice.Local)?.version

    /** Whether this device's engine is in use, configured or not; false only while a remote is selected. */
    fun isLocal(context: Context): Boolean = current(context) !is EngineChoice.Remote

    /** Switches to this device's engine, on the version it last used when that is still installed. */
    fun selectLocal(context: Context) {
        val installed = EngineFiles.store(context).installed()
        select(context, EngineChoices.local(Prefs(context).lastLocalVersion, installed))
    }

    fun remote(context: Context): RemoteEngine? {
        val c = current(context) as? EngineChoice.Remote ?: return null
        return Prefs(context).remotes.firstOrNull { it.id == c.id }
    }

    /**
     * Connects to the selected remote engine when nothing runs now. There is no connect step for
     * the user: while the app is open it follows whichever remote is selected, and the service drops
     * the connection once the app is closed and nothing plays or casts through it.
     */
    fun followRemote(context: Context) {
        if (current(context) !is EngineChoice.Remote) return
        val s = EngineController.sessionState
        if (s == EngineState.Stopped || s is EngineState.Crashed) EngineController.start(context)
    }

    /** Makes [choice] the selection, stopping or restarting what runs so it matches. */
    fun select(context: Context, choice: EngineChoice?) {
        val before = current(context)
        store(context, choice)
        if (choice == before) return
        val s = EngineController.sessionState
        // An error belongs to the engine that failed, not to the one chosen next.
        if (s is EngineState.Crashed) EngineController.update(EngineState.Stopped, clearVersion = true)
        val active = s != EngineState.Stopped && s !is EngineState.Crashed
        if (active) {
            when {
                choice is EngineChoice.Local && before is EngineChoice.Local -> {
                    if (EngineController.runningVersion != choice.version) EngineController.restart(context)
                }
                else -> EngineController.stop(context)
            }
        }
        // A remote chosen in the open app connects at once; the queued stop above runs first.
        if (choice is EngineChoice.Remote && EngineController.visibleScreens > 0) EngineController.start(context)
    }

    /** A finished download; it becomes the selection only when nothing is selected. */
    fun installed(context: Context, version: String) {
        store(context, EngineChoices.afterInstall(current(context), version))
    }

    /** Deletes a downloaded version; false (and nothing removed) while that version runs or is starting. */
    fun removeLocal(context: Context, version: String): Boolean {
        if (version == EngineController.runningVersion) return false
        // A queued start sets the running version only once it runs; the selected version is the one it will launch.
        val starting = EngineController.sessionState == EngineState.Starting && localVersion(context) == version
        if (starting) return false
        EngineFiles.store(context).remove(version)
        store(context, EngineChoices.afterRemove(current(context), EngineChoice.Local(version)))
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
        // An edit of the engine we are connected to (address or name) takes effect at once; a remote
        // reconnect touches no stream, so the player keeps going.
        val connected = (EngineController.sessionTarget as? EngineTarget.Remote)?.engine
        if (connected?.id == r.id && connected != r) EngineController.restart(context)
        return true
    }
}
