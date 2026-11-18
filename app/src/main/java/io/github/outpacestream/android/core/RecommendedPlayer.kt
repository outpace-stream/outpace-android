package io.github.outpacestream.android.core

/**
 * VLC is the player Outpace recommends, never one it requires. Some TV stock players stall on the
 * live MPEG-TS hand-off (a TCL TV's `com.tcl.ui_mediaCenter` blocks in prepare until it ANRs),
 * while VLC plays the same URL reliably. Every other player stays available through the chooser.
 */
object RecommendedPlayer {
    const val VLC_PACKAGE = "org.videolan.vlc"

    /** The store listing; offered only when an app on the device handles it. */
    const val STORE_URI = "market://details?id=$VLC_PACKAGE"

    /**
     * What the play screen offers, from the packages of the activities that can play the stream
     * (Outpace's own already removed), in the order the system returned them.
     */
    fun offer(handlers: List<String>, hintDismissed: Boolean, storeAvailable: Boolean): PlayerOffer {
        val vlc = handlers.indexOf(VLC_PACKAGE).takeIf { it >= 0 }
        val hint = vlc == null && !hintDismissed
        return PlayerOffer(vlc, hint, hint && storeAvailable)
    }

    /** Indices into [handlers] with VLC's activities first and the rest in their original order. */
    fun vlcFirst(handlers: List<String>): List<Int> = handlers.indices.sortedBy { if (handlers[it] == VLC_PACKAGE) 0 else 1 }
}

/**
 * [vlc] is the index of the VLC activity to open directly, null when VLC cannot play the stream.
 * [hint] shows the "install VLC" hint, and [storeLink] its store button.
 */
data class PlayerOffer(val vlc: Int?, val hint: Boolean, val storeLink: Boolean)
