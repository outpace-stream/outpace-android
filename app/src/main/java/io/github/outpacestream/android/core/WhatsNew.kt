package io.github.outpacestream.android.core

/**
 * Whether the first launch of a new app version shows its release notes. Pure, so the rules are
 * unit tested; the caller stores [Seen] for the running build whatever the decision.
 */
object WhatsNew {
    /** The last build that ran, as stored in preferences. */
    data class Seen(val code: Int, val name: String)

    sealed interface Decision {
        data object Skip : Decision

        /** Show the notes of the versions after [previous] up to the running one; null means that one only. */
        data class Show(val previous: String?) : Decision
    }

    /**
     * [seen] is null until a build with this feature has run. [updated] says whether Android
     * installed this build over an earlier one rather than fresh, which tells an update from an
     * older build (no [seen] yet) apart from a fresh install.
     */
    fun decide(seen: Seen?, currentCode: Int, currentName: String, updated: Boolean): Decision {
        if (currentName == AppRelease.DEV_VERSION) return Decision.Skip
        if (seen == null) return if (updated) Decision.Show(null) else Decision.Skip
        if (currentCode <= seen.code) return Decision.Skip
        return Decision.Show(seen.name.takeIf { it != AppRelease.DEV_VERSION })
    }
}
