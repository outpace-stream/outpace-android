package io.github.outpacestream.android.ui

import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.outpacestream.android.BuildConfig
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.AppRelease
import io.github.outpacestream.android.core.Releases
import io.github.outpacestream.android.core.UpdatePolicy
import io.github.outpacestream.android.net.AppUpdates
import io.github.outpacestream.android.net.EngineReleases

/** App settings. Engine settings live in the Engines tab, next to the engine they configure. */
class SettingsScreen(host: MainActivity, parent: ViewGroup) : Screen(host, parent, R.layout.screen_settings) {
    private val rows = SettingRows(host) { render() }
    private val bootRow: View
    private val batteryRow: View

    private lateinit var updateStatus: View
    private lateinit var updateCheck: View
    private lateinit var updateChangelog: View
    private lateinit var updateInstall: View
    private lateinit var updateNotify: View
    private var checking = false
    private var checkError: String? = null

    init {
        val group = find<LinearLayout>(R.id.group_background)
        bootRow = rows.toggle(group, R.string.settings_boot, R.string.settings_boot_summary, { prefs.startOnBoot }) { prefs.startOnBoot = it }
        rows.toggle(group, R.string.settings_idle, R.string.settings_idle_summary, { prefs.idleStop }) { prefs.idleStop = it }
        batteryRow = rows.row(group, R.string.settings_battery, R.string.settings_battery_summary)
        batteryRow.setOnClickListener { BatteryPrompt.request(host) }
        buildUpdates(find(R.id.group_updates))
        find<TextView>(R.id.about).text = host.getString(R.string.settings_about, BuildConfig.VERSION_NAME, Releases.REPO, AppRelease.REPO.slug)
    }

    override fun focusTarget(): View = bootRow

    override fun onShown() = rows.refresh()

    private fun buildUpdates(group: LinearLayout) {
        val installed = BuildConfig.VERSION_NAME
        val dev = installed == AppRelease.DEV_VERSION
        updateStatus = rows.row(group, R.string.settings_update_status, R.string.settings_update_never_checked).apply {
            findViewById<TextView>(R.id.title).text = host.getString(R.string.settings_update_status, installed)
            isFocusable = false
            isClickable = false
        }
        updateCheck = rows.row(group, R.string.settings_update_check, R.string.settings_update_check_summary)
        updateCheck.setOnClickListener { checkNow() }
        updateChangelog = rows.row(group, R.string.settings_update_changelog, R.string.settings_update_changelog_summary)
        updateChangelog.setOnClickListener { prefs.appLatestVersion?.let { host.updateFlow.showChangelog(installed, it) } }
        updateInstall = rows.row(group, R.string.settings_update_install, R.string.settings_update_none)
        updateInstall.setOnClickListener { prefs.appLatestVersion?.let { host.updateFlow.startUpdate(it) } }
        updateNotify = rows.toggle(group, R.string.settings_update_notify, R.string.settings_update_notify_summary, { prefs.appUpdateNotify }) {
            prefs.appUpdateNotify = it
        }
        setEnabled(updateCheck, !dev)
        setEnabled(updateNotify, !dev)
        host.updateFlow.onChanged = {
            rows.refresh()
            renderUpdates()
        }
    }

    private fun checkNow() {
        if (checking) return
        checking = true
        checkError = null
        renderUpdates()
        background.execute {
            val error = try {
                AppUpdates(host).latest(force = true)
                null
            } catch (e: Exception) {
                EngineReleases.describe(e)
            }
            host.onMain {
                checking = false
                checkError = error
                renderUpdates()
            }
        }
    }

    /** Disabled rows ignore clicks and look dimmed, since the row background has no disabled state. */
    private fun setEnabled(row: View, enabled: Boolean) {
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.5f
    }

    private fun renderUpdates() {
        val installed = BuildConfig.VERSION_NAME
        val latest = prefs.appLatestVersion
        val newer = UpdatePolicy.isNewer(installed, latest)
        val checkedAt = prefs.appLatestCheckedAt
        updateStatus.findViewById<TextView>(R.id.summary).text = when {
            installed == AppRelease.DEV_VERSION -> host.getString(R.string.settings_update_dev)
            checkError != null -> host.getString(R.string.settings_update_check_failed, checkError)
            newer && checkedAt != 0L -> host.getString(R.string.settings_update_available_checked, latest, DateUtils.getRelativeTimeSpanString(checkedAt))
            newer -> host.getString(R.string.settings_update_available, latest)
            latest == null || checkedAt == 0L -> host.getString(R.string.settings_update_never_checked)
            else -> host.getString(R.string.settings_update_up_to_date, DateUtils.getRelativeTimeSpanString(checkedAt))
        }
        updateCheck.findViewById<TextView>(R.id.summary).setText(
            if (checking) R.string.settings_update_checking else R.string.settings_update_check_summary,
        )
        setEnabled(updateChangelog, newer)
        setEnabled(updateInstall, newer && !host.updateFlow.busy)
        updateInstall.findViewById<TextView>(R.id.summary).text =
            if (newer) host.getString(R.string.settings_update_install_summary, latest) else host.getString(R.string.settings_update_none)
    }

    override fun render() {
        batteryRow.visibility = if (BatteryPrompt.isExempt(host)) View.GONE else View.VISIBLE
        renderUpdates()
    }
}
