package io.github.outpacestream.android.core

/** The engine the app uses: a downloaded version on this device, or a remote engine by id. */
sealed interface EngineChoice {
    data class Local(val version: String) : EngineChoice

    data class Remote(val id: String) : EngineChoice
}

/** What the engine service runs or is connected to. */
sealed interface EngineTarget {
    data class Local(val version: String) : EngineTarget

    data class Remote(val engine: RemoteEngine) : EngineTarget
}

/** Storage and the rules for how the selection changes. Null means nothing is selected. */
object EngineChoices {
    private const val NONE = "none"
    private const val LOCAL = "local:"
    private const val REMOTE = "remote:"

    fun encode(c: EngineChoice?): String = when (c) {
        null -> NONE
        is EngineChoice.Local -> LOCAL + c.version
        is EngineChoice.Remote -> REMOTE + c.id
    }

    fun decode(s: String?): EngineChoice? = when {
        s == null -> null
        s.startsWith(LOCAL) -> s.removePrefix(LOCAL).takeIf { it.isNotEmpty() }?.let { EngineChoice.Local(it) }
        s.startsWith(REMOTE) -> s.removePrefix(REMOTE).takeIf { it.isNotEmpty() }?.let { EngineChoice.Remote(it) }
        else -> null
    }

    /**
     * The first selection for an install that predates it: what it ran before, that is the stored
     * active version when still installed, else the newest installed one ([installed] is newest first).
     */
    fun migrate(legacyActive: String?, installed: List<String>): EngineChoice? {
        val version = legacyActive?.takeIf { it in installed } ?: installed.firstOrNull() ?: return null
        return EngineChoice.Local(version)
    }

    /** [c] while it still exists; a removed version or remote means nothing is selected. */
    fun resolve(c: EngineChoice?, installed: List<String>, remotes: List<RemoteEngine>): EngineChoice? = when (c) {
        null -> null
        is EngineChoice.Local -> c.takeIf { c.version in installed }
        is EngineChoice.Remote -> c.takeIf { remotes.any { it.id == c.id } }
    }

    /** A download becomes the selection only when nothing is selected. */
    fun afterInstall(c: EngineChoice?, version: String): EngineChoice? = c ?: EngineChoice.Local(version)

    fun afterRemove(c: EngineChoice?, removed: EngineChoice): EngineChoice? = if (c == removed) null else c

    /** The selection a running session belongs to. */
    fun of(target: EngineTarget?): EngineChoice? = when (target) {
        null -> null
        is EngineTarget.Local -> EngineChoice.Local(target.version)
        is EngineTarget.Remote -> EngineChoice.Remote(target.engine.id)
    }

    /**
     * Whether the running session (if any) is the selected engine. Right after a switch the old
     * session lingers until its queued stop runs; screens must not show or use it as the new one.
     */
    fun sessionMatches(target: EngineTarget?, selected: EngineChoice?): Boolean = target == null || of(target) == selected
}
