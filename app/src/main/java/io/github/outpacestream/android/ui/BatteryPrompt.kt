package io.github.outpacestream.android.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.github.outpacestream.android.Device
import io.github.outpacestream.android.Prefs

object BatteryPrompt {
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Asks once, on TV or when start-on-boot is on, where background kills hurt most. */
    /** Returns whether it asked, so other prompts can wait for a later launch. */
    fun maybeShow(activity: Activity): Boolean {
        val prefs = Prefs(activity)
        if (prefs.batteryPromptShown || isExempt(activity)) return false
        if (!Device.isTv(activity) && !prefs.startOnBoot) return false
        prefs.batteryPromptShown = true
        request(activity)
        return true
    }

    @SuppressLint("BatteryLife")
    fun request(activity: Activity) {
        if (Build.VERSION.SDK_INT < 23) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(direct, list)) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
                // Some TV builds ship without these settings screens.
            }
        }
    }
}
