package io.github.outpacestream.android.ui

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.github.outpacestream.android.Device
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.core.BatteryOptimisation

object BatteryPrompt {
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** `EXTRA_PRESENT` from the sticky battery broadcast, or null when it is missing. */
    private fun batteryPresent(context: Context): Boolean? {
        val status = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return null
        if (!status.hasExtra(BatteryManager.EXTRA_PRESENT)) return null
        return status.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
    }

    @SuppressLint("BatteryLife")
    @TargetApi(23)
    private fun directIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    @TargetApi(23)
    private fun listIntent() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * The screen the Settings row opens, or null when the row should be hidden. With
     * [checkExempt] false the exemption is left out, for callers that check it more often.
     */
    fun screen(context: Context, checkExempt: Boolean = true): BatteryOptimisation.Screen? {
        if (Build.VERSION.SDK_INT < 23) return null
        val pm = context.packageManager
        return BatteryOptimisation.screen(
            sdkInt = Build.VERSION.SDK_INT,
            exempt = checkExempt && isExempt(context),
            batteryLess = BatteryOptimisation.batteryLess(Device.isTv(context), batteryPresent(context)),
            directResolves = directIntent(context).resolveActivity(pm) != null,
            listResolves = listIntent().resolveActivity(pm) != null,
        )
    }

    /**
     * Asks once when start-on-boot is on, where background kills hurt most. Returns whether it
     * asked, so other prompts can wait for a later launch.
     */
    fun maybeShow(activity: Activity): Boolean {
        val prefs = Prefs(activity)
        if (!BatteryOptimisation.shouldPrompt(prefs.batteryPromptShown, prefs.startOnBoot)) return false
        val screen = screen(activity) ?: return false
        prefs.batteryPromptShown = true
        return open(activity, screen)
    }

    /** Opens [screen], falling back from the direct prompt to the list; returns whether one opened. */
    fun open(activity: Activity, screen: BatteryOptimisation.Screen): Boolean {
        val intents = when (screen) {
            BatteryOptimisation.Screen.Direct -> listOf(directIntent(activity), listIntent())
            BatteryOptimisation.Screen.List -> listOf(listIntent())
        }
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: Exception) {
                // A resolved screen can still refuse to start; try the next one.
            }
        }
        return false
    }
}
