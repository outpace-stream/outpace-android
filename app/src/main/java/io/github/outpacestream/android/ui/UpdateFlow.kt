package io.github.outpacestream.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.outpacestream.android.BuildConfig
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.AppRelease
import io.github.outpacestream.android.core.Mailbox
import io.github.outpacestream.android.core.UpdatePolicy
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.net.AppUpdates
import io.github.outpacestream.android.net.EngineReleases

/**
 * The app update dialogs: the prompt, the changelog, the install permission step and the
 * download. Owned by [MainActivity], which forwards [attach], [detach] and [onResumed].
 */
class UpdateFlow(private val activity: Activity) {
    private val prefs = Prefs(activity)
    private val updates = AppUpdates(activity)
    private var prompt: AlertDialog? = null
    private var progress: AlertDialog? = null
    private var pendingVersion: String? = null
    private var progressBar: ProgressBar? = null
    private var progressLabel: TextView? = null

    /** Called when something the Settings screen shows may have changed. */
    var onChanged: (() -> Unit)? = null

    /** True while a download or the install hand-off runs, in this or an earlier activity instance. */
    val busy: Boolean get() = running

    // Receivers are fields so detach() can remove exactly what attach() added.
    private val onUserAction: (Intent) -> Unit = { intent -> runCatching { activity.startActivity(intent) } }
    private val onInstallFailure: (String) -> Unit = { message -> showInstallFailure(message); onChanged?.invoke() }
    private val onDownload: (DownloadEvent) -> Unit = { event -> activity.onMain { handle(event) } }

    /** Binds to the process-wide download and install results; replays what arrived while detached. */
    fun attach() {
        AppInstaller.userActions.attach(onUserAction)
        AppInstaller.failures.attach(onInstallFailure)
        if (running) showProgress(null)
        events.attach(onDownload)
    }

    fun detach() {
        AppInstaller.userActions.detach(onUserAction)
        AppInstaller.failures.detach(onInstallFailure)
        events.detach(onDownload)
        // The download carries on; the next attach shows its progress again.
        progress?.dismiss()
        progress = null
    }

    /**
     * Android kills the app when "Install unknown apps" changes, so the version waiting for that
     * permission travels in the activity's saved state rather than only in memory.
     */
    fun saveState(out: Bundle) {
        pendingVersion?.let { out.putString(STATE_PENDING, it) }
    }

    fun restoreState(state: Bundle?) {
        state?.getString(STATE_PENDING)?.let { pendingVersion = it }
    }

    /** Continues an update once the user is back from "Install unknown apps". */
    fun onResumed() {
        val version = pendingVersion ?: return
        pendingVersion = null
        if (AppInstaller.canInstall(activity)) download(version) else showMessage(activity.getString(R.string.update_permission_denied))
    }

    fun isPromptShowing() = prompt?.isShowing == true

    /** The automatic dialog: Changelog, Update, Not now, Never ask again. */
    fun showPrompt(installed: String, latest: String) {
        if (isPromptShowing() || busy) return
        val pad = activity.dp(24)
        val message = TextView(activity).apply {
            text = activity.getString(R.string.update_available, installed, latest)
            textSize = 16f
        }
        // A fourth action does not fit AlertDialog's three buttons; it sits in the body, reachable by D-pad.
        val changelog = Button(activity).apply {
            setText(R.string.update_changelog)
            setOnClickListener { showChangelog(installed, latest) }
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(message)
            addView(changelog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad / 2
            })
        }
        prompt = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setView(body)
            .setPositiveButton(R.string.update_action) { _, _ -> startUpdate(latest) }
            .setNegativeButton(R.string.update_not_now, null)
            .setNeutralButton(R.string.update_never) { _, _ ->
                prefs.appUpdateNotify = false
                activity.toast(activity.getString(R.string.update_never_toast))
                onChanged?.invoke()
            }
            .setOnDismissListener { prompt = null }
            .show()
        prompt?.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    /** Release notes of every version newer than [installed], fetched on demand. */
    fun showChangelog(installed: String, latest: String) {
        val loading = AlertDialog.Builder(activity)
            .setTitle(R.string.update_changelog_title)
            .setMessage(R.string.update_changelog_loading)
            .setNegativeButton(R.string.close, null)
            .show()
        background.execute {
            val result = runCatching { updates.changelog(installed) }
            activity.onMain {
                if (!loading.isShowing) return@onMain
                loading.dismiss()
                result.fold(
                    onSuccess = { text -> showNotes(text.ifEmpty { activity.getString(R.string.update_changelog_empty) }, latest) },
                    onFailure = { e -> showNotesFailed(EngineReleases.describe(e as? Exception ?: Exception(e)), latest) },
                )
            }
        }
    }

    private fun showNotes(text: String, latest: String) {
        val pad = activity.dp(24)
        // A focusable ScrollView scrolls with the D-pad on TV.
        val scroll = ScrollView(activity).apply {
            isFocusable = true
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(activity).apply {
                this.text = text
                setTextIsSelectable(false)
                textSize = 15f
            })
        }
        val b = AlertDialog.Builder(activity)
            .setTitle(R.string.update_changelog_title)
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
        if (canUpdateTo(latest)) b.setPositiveButton(R.string.update_action) { _, _ -> startUpdate(latest) }
        b.show()
        scroll.requestFocus()
    }

    private fun showNotesFailed(reason: String, latest: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_changelog_title)
            .setMessage(activity.getString(R.string.update_changelog_failed, reason))
            .setNeutralButton(R.string.update_open_release) { _, _ -> openReleasePage(latest) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Opens the release page, or shows its address when the device has no browser. */
    fun openReleasePage(version: String) {
        val url = AppRelease.REPO.releasePage(version)
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            showMessage(activity.getString(R.string.update_release_url, url))
        }
    }

    /** The Update action: playback warning, install permission, then download and install. */
    fun startUpdate(latest: String) {
        if (busy || !canUpdateTo(latest)) return
        prompt?.dismiss()
        if (EngineController.streams.isNotEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_title)
                .setMessage(R.string.update_stops_playback)
                .setPositiveButton(R.string.update_continue) { _, _ -> checkPermissionThenDownload(latest) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            checkPermissionThenDownload(latest)
        }
    }

    private fun canUpdateTo(latest: String) = UpdatePolicy.isNewer(BuildConfig.VERSION_NAME, latest)

    private fun checkPermissionThenDownload(latest: String) {
        if (AppInstaller.canInstall(activity)) {
            download(latest)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(R.string.update_permission_needed)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                if (AppInstaller.openInstallPermission(activity)) {
                    pendingVersion = latest
                } else {
                    showMessage(activity.getString(R.string.update_permission_manual))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun download(version: String) {
        if (running) return
        running = true
        cancelled = false
        events.clear()
        showProgress(version)
        val app = activity.applicationContext
        // The task outlives this activity on rotation, so it holds only the application context.
        val updates = AppUpdates(app)
        background.execute {
            val apk = try {
                updates.download(version, { cancelled }) { done, total -> events.post(DownloadEvent.Progress(version, done, total)) }
            } catch (e: Exception) {
                runCatching { updates.discard() }
                val message = if (cancelled) null else app.getString(R.string.update_download_failed, EngineReleases.describe(e))
                finish(DownloadEvent.Finished(message, installFailed = false))
                return@execute
            }
            events.post(DownloadEvent.Installing(version))
            try {
                AppInstaller.install(app, apk)
                finish(DownloadEvent.Finished(null, installFailed = false))
            } catch (e: Exception) {
                runCatching { updates.discard() }
                finish(DownloadEvent.Finished(app.getString(R.string.update_install_failed, EngineReleases.describe(e)), installFailed = true))
            }
        }
    }

    private fun finish(event: DownloadEvent.Finished) {
        running = false
        events.post(event)
    }

    /** Shows the progress dialog, or keeps the one already shown. [version] null while reattaching. */
    private fun showProgress(version: String?) {
        if (progress?.isShowing == true) return
        val pad = activity.dp(24)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val label = TextView(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
            addView(label)
        }
        progressBar = bar
        progressLabel = label
        progress = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_downloading, version ?: prefs.appLatestVersion.orEmpty()))
            .setView(body)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .show()
    }

    private fun handle(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.Progress -> {
                showProgress(event.version)
                val bar = progressBar ?: return
                val label = progressLabel ?: return
                if (event.total > 0) {
                    bar.isIndeterminate = false
                    bar.max = 1000
                    bar.progress = (event.done * 1000 / event.total).toInt()
                    label.text = activity.getString(
                        R.string.update_progress,
                        Formatter.formatShortFileSize(activity, event.done),
                        Formatter.formatShortFileSize(activity, event.total),
                    )
                } else {
                    label.text = Formatter.formatShortFileSize(activity, event.done)
                }
            }
            is DownloadEvent.Installing -> {
                showProgress(event.version)
                progressLabel?.setText(R.string.update_installing)
            }
            is DownloadEvent.Finished -> {
                progress?.dismiss()
                progress = null
                event.error?.let { if (event.installFailed) showInstallFailure(it) else showMessage(it) }
                onChanged?.invoke()
            }
        }
    }

    /** An install that could not finish: say why and offer the release page to install by hand. */
    private fun showInstallFailure(text: String) {
        val version = prefs.appLatestVersion
        val b = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
        if (version != null) b.setNeutralButton(R.string.update_open_release) { _, _ -> openReleasePage(version) }
        b.show()
    }

    private fun showMessage(text: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Download progress and outcome, shared across activity instances (rotation, Home and back). */
    sealed interface DownloadEvent {
        data class Progress(val version: String, val done: Long, val total: Long) : DownloadEvent
        data class Installing(val version: String) : DownloadEvent
        data class Finished(val error: String?, val installFailed: Boolean) : DownloadEvent
    }

    private companion object {
        const val STATE_PENDING = "app_update_pending"

        @Volatile var running = false
        @Volatile var cancelled = false
        val events = Mailbox<DownloadEvent>()
    }
}
