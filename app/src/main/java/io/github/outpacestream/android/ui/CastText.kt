package io.github.outpacestream.android.ui

import android.content.Context
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.CastError
import io.github.outpacestream.android.core.CastFailure
import io.github.outpacestream.android.core.CastMessage
import io.github.outpacestream.android.core.EngineSettings

/** The localized line for an active or ended cast, shared by the cast screen, Home and the notification. */
fun Context.castText(message: CastMessage, tv: String): String = when (message) {
    CastMessage.Starting -> getString(R.string.cast_status_starting, tv)
    CastMessage.Playing -> getString(R.string.cast_playing_on, tv)
    CastMessage.Paused -> getString(R.string.cast_status_paused, tv)
    CastMessage.NotFetching -> getString(R.string.cast_status_not_fetching, tv)
    CastMessage.TvStopped -> getString(R.string.cast_status_tv_stopped, tv)
    CastMessage.TvNotFound -> getString(R.string.cast_status_not_found, tv)
    CastMessage.Ended -> getString(R.string.cast_status_ended, tv)
    CastMessage.EngineUnreachable -> getString(R.string.cast_status_engine_unreachable)
    CastMessage.StopFailed -> getString(R.string.cast_status_stop_failed, tv)
}

/** Why a cast request failed. [remote] names the remote engine, whose configuration is its own. */
fun Context.castErrorText(error: CastError, remote: String?): String = when (error.failure) {
    CastFailure.Disabled ->
        if (remote != null) getString(R.string.cast_err_remote_off, remote, EngineSettings.CAST_MIN_VERSION) else getString(R.string.cast_err_off)
    CastFailure.UnknownTarget -> getString(R.string.cast_err_unknown_target)
    CastFailure.LoopbackBind -> getString(R.string.cast_err_loopback)
    CastFailure.UnsupportedBind -> getString(R.string.cast_err_ipv6)
    CastFailure.StreamNotFound -> getString(R.string.cast_err_no_stream)
    CastFailure.NoPeers -> getString(R.string.start_no_peers)
    CastFailure.StreamUnavailable -> getString(R.string.cast_err_unavailable)
    CastFailure.Capacity -> getString(R.string.cast_err_capacity)
    CastFailure.RendererFault ->
        error.upnpError?.let { getString(R.string.cast_err_refused_code, it.toString()) } ?: getString(R.string.cast_err_refused)
    CastFailure.RendererUnreachable -> getString(R.string.cast_err_tv_unreachable)
    CastFailure.RendererTimeout -> getString(R.string.cast_err_tv_timeout)
    CastFailure.Discovery -> getString(R.string.cast_err_discovery)
    CastFailure.Network -> getString(R.string.start_network)
    CastFailure.InvalidRequest, CastFailure.Other -> getString(R.string.cast_err_other, error.message ?: "")
}
