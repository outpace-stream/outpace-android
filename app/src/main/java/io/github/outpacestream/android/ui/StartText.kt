package io.github.outpacestream.android.ui

import android.content.Context
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.StartMessage
import io.github.outpacestream.android.core.StartView

/** The localized line for a stream start, shared by the play screen and the engine notification. */
fun Context.startText(view: StartView): String {
    val text = when (view.message) {
        StartMessage.Waiting -> getString(R.string.start_waiting)
        StartMessage.Resolving -> getString(R.string.start_resolving)
        StartMessage.Connecting -> getString(R.string.start_connecting, view.peers)
        StartMessage.Buffering -> getString(R.string.start_buffering)
        StartMessage.NoPeersYet -> getString(R.string.start_no_peers_yet)
        StartMessage.NoPeers -> getString(R.string.start_no_peers)
        StartMessage.Network -> getString(R.string.start_network)
        StartMessage.NotAStream, StartMessage.Unresolvable -> getString(R.string.start_not_a_stream)
        StartMessage.Protected -> getString(R.string.start_protected)
        StartMessage.Capacity -> getString(R.string.start_capacity)
        StartMessage.NoSpace -> getString(R.string.start_no_space)
        StartMessage.Failed -> getString(R.string.start_failed)
    }
    return view.detail?.let { getString(R.string.start_with_detail, text, it) } ?: text
}
