package io.github.outpacestream.android.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.outpacestream.android.BuildConfig
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.AppRelease
import io.github.outpacestream.android.core.BatteryOptimisation
import io.github.outpacestream.android.core.Releases
import io.github.outpacestream.android.core.UpdatePolicy
import io.github.outpacestream.android.net.AppUpdates
import io.github.outpacestream.android.net.EngineReleases

/**
 * Settings: the engine connection and this device's engine first (see [EngineSections]), then the
 * app's own settings.
 */
class SettingsScreen(host: MainActivity, parent: ViewGroup) : Screen(host, parent, R.layout.screen_settings) {
    private val rows = SettingRows(host) { render() }
    private val engines = EngineSections(host, view)
    private val bootRow: View
    private val batteryRow: View

    /**
     * Where the battery row leads, ignoring the exemption, refreshed when the tab appears; null
     * hides the row. The exemption itself is checked on every render, since the direct prompt is
     * a dialog that returns without stopping this activity.
     */
    private var batteryScreen: BatteryOptimisation.Screen? = null

    private lateinit var updateStatus: View
    private lateinit var updateTitle: TextView
    private lateinit var updateSummary: TextView
    private lateinit var updateAction: Button
    private lateinit var whatsNew: View
    private lateinit var updateNotify: View

    /**
     * The newer version the status row shows, or null while it shows Check. Clicks act on what is
     * on screen: a check can store a newer version just before the redraw that would show it.
     */
    private var shownUpdate: String? = null
    private var checking = false
    private var checkError: String? = null

    init {
        val group = find<LinearLayout>(R.id.group_background)
        bootRow = rows.toggle(group, R.string.settings_boot, R.string.settings_boot_summary, { prefs.startOnBoot }) { prefs.startOnBoot = it }
        rows.toggle(group, R.string.settings_idle, R.string.settings_idle_summary, { prefs.idleStop }) { prefs.idleStop = it }
        batteryRow = rows.row(group, R.string.settings_battery, R.string.settings_battery_summary)
        batteryRow.setOnClickListener { batteryScreen?.let { BatteryPrompt.open(host, it) } }
        buildUpdates(find(R.id.group_updates))
        // The version is in App updates above, so About only says where releases come from.
        find<TextView>(R.id.about).text = host.getString(R.string.settings_about, Releases.REPO, AppRelease.REPO.slug)
        rows.row(find(R.id.group_about), R.string.settings_licenses, R.string.settings_licenses_summary)
            .setOnClickListener { host.open(LicensesActivity::class.java) }
    }

    override fun focusTarget(): View = engines.focusTarget()

    override fun onShown() {
        rows.refresh()
        engines.onShown()
    }

    override fun onVisible() {
        batteryScreen = BatteryPrompt.screen(host, checkExempt = false)
        engines.onVisible()
    }

    override fun onInvisible() = engines.onInvisible()

    /**
     * Three rows: the status with one button that checks or updates, release notes that follow
     * the status (the installed version's, or everything newer once an update exists), and the
     * notify switch. Development builds can't update, so they get no button.
     */
    private fun buildUpdates(group: LinearLayout) {
        val installed = BuildConfig.VERSION_NAME
        val dev = installed == AppRelease.DEV_VERSION
        updateStatus = LayoutInflater.from(host).inflate(R.layout.item_setting_action, group, false)
        group.addView(updateStatus)
        updateTitle = updateStatus.findViewById(R.id.title)
        updateSummary = updateStatus.findViewById(R.id.summary)
        updateAction = updateStatus.findViewById(R.id.action)
        updateAction.setOnClickListener {
            val shown = shownUpdate
            if (shown != null && UpdatePolicy.isNewer(installed, shown)) host.updateFlow.startUpdate(shown) else checkNow()
        }
        updateAction.visibility = if (dev) View.GONE else View.VISIBLE
        whatsNew = rows.row(group, R.string.settings_whats_new, R.string.settings_whats_new_summary)
        whatsNew.setOnClickListener {
            val shown = shownUpdate
            if (shown != null) {
                host.updateFlow.showChangelog(installed, shown)
            } else {
                host.updateFlow.showWhatsNew(null, installed, quiet = false)
            }
        }
        updateNotify = rows.toggle(group, R.string.settings_update_notify, R.string.settings_update_notify_summary, { prefs.appUpdateNotify }) {
            prefs.appUpdateNotify = it
        }
        setEnabled(whatsNew, !dev)
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
        val newer = UpdatePolicy.isNewer(installed, latest) && latest != null
        shownUpdate = if (newer) latest else null
        val checkedAt = prefs.appLatestCheckedAt
        // DateUtils says "0 minutes ago" for the first minute; a check that recent reads "just now".
        val checked: CharSequence? = when {
            checkedAt == 0L -> null
            System.currentTimeMillis() - checkedAt < DateUtils.MINUTE_IN_MILLIS -> host.getString(R.string.settings_update_just_now)
            else -> DateUtils.getRelativeTimeSpanString(checkedAt)
        }
        updateTitle.text = host.getString(
            if (newer) R.string.settings_update_title_available else R.string.settings_update_title_current,
            if (newer) latest else installed,
        )
        updateSummary.text = when {
            installed == AppRelease.DEV_VERSION -> host.getString(R.string.settings_update_dev)
            checking -> host.getString(R.string.settings_update_checking)
            checkError != null -> host.getString(R.string.settings_update_check_failed, checkError)
            newer && checked != null -> host.getString(R.string.settings_update_you_have_checked, installed, checked)
            newer -> host.getString(R.string.settings_update_you_have, installed)
            latest == null || checked == null -> host.getString(R.string.settings_update_never_checked)
            else -> host.getString(R.string.settings_update_up_to_date, checked)
        }
        // One button: Update (primary) when a newer version exists, otherwise Check.
        updateAction.setText(if (newer) R.string.settings_update_install else R.string.settings_update_check_short)
        updateAction.setBackgroundResource(if (newer) R.drawable.bg_button_primary else R.drawable.bg_button_secondary)
        @Suppress("DEPRECATION") // Resources.getColorStateList(int, Theme) needs API 23; these colors ignore the theme.
        updateAction.setTextColor(host.resources.getColorStateList(if (newer) R.color.button_primary_text else R.color.button_secondary_text))
        updateAction.isEnabled = if (newer) !host.updateFlow.busy else !checking
        whatsNew.findViewById<TextView>(R.id.title).text = host.getString(R.string.whats_new_title, if (newer) latest else installed)
        whatsNew.findViewById<TextView>(R.id.summary).setText(
            if (newer) R.string.settings_update_changelog_summary else R.string.settings_whats_new_summary,
        )
    }

    override fun render() {
        engines.render()
        batteryRow.visibility = if (batteryScreen == null || BatteryPrompt.isExempt(host)) View.GONE else View.VISIBLE
        renderUpdates()
    }
}
