package io.github.outpacestream.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
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
    @Volatile private var cancelled = false

    /** Called when something the Settings screen shows may have changed. */
    var onChanged: (() -> Unit)? = null

    /** True while the download or the install hand-off is running. */
    var busy = false
        private set

    fun attach() {
        AppInstaller.onUserAction = { intent -> runCatching { activity.startActivity(intent) } }
        AppInstaller.onResult = { _, message -> activity.onMain { message?.let { showMessage(it) }; onChanged?.invoke() } }
    }

    fun detach() {
        AppInstaller.onUserAction = null
        AppInstaller.onResult = null
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
        busy = true
        cancelled = false
        val pad = activity.dp(24)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val label = TextView(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
            addView(label)
        }
        progress = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_downloading, version))
            .setView(body)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .show()
        background.execute {
            try {
                val apk = updates.download(version, { cancelled }) { done, total ->
                    activity.onMain {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.max = 1000
                            bar.progress = (done * 1000 / total).toInt()
                            label.text = activity.getString(
                                R.string.update_progress,
                                Formatter.formatShortFileSize(activity, done),
                                Formatter.formatShortFileSize(activity, total),
                            )
                        } else {
                            label.text = Formatter.formatShortFileSize(activity, done)
                        }
                    }
                }
                activity.onMain { label.setText(R.string.update_installing) }
                AppInstaller.install(activity, apk)
                activity.onMain { finishDownload(null) }
            } catch (e: Exception) {
                runCatching { updates.discard() }
                val message = if (cancelled) null else activity.getString(R.string.update_download_failed, EngineReleases.describe(e))
                activity.onMain { finishDownload(message) }
            }
        }
    }

    private fun finishDownload(error: String?) {
        busy = false
        progress?.dismiss()
        progress = null
        error?.let { showMessage(it) }
        onChanged?.invoke()
    }

    private fun showMessage(text: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
