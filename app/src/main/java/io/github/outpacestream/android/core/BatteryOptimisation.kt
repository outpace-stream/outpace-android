package io.github.outpacestream.android.core

/**
 * Whether and how to offer the battery optimisation exemption. Doze and App Standby only apply
 * while a device runs on battery, so a TV or a device without a battery never needs it.
 */
object BatteryOptimisation {
    /** The settings screen to open for the exemption. */
    enum class Screen {
        /** `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: asks about this app directly. */
        Direct,

        /** `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`: the list of all apps. */
        List,
    }

    /**
     * True on a TV or when the battery broadcast says no battery is present. [batteryPresent] is
     * null when the broadcast is missing, which counts as having a battery.
     */
    fun batteryLess(isTv: Boolean, batteryPresent: Boolean?): Boolean = isTv || batteryPresent == false

    /**
     * The screen to open, or null when the Settings row should be hidden: below API 23 (no Doze),
     * when already exempt, on a battery-less device, or when neither screen resolves.
     */
    fun screen(
        sdkInt: Int,
        exempt: Boolean,
        batteryLess: Boolean,
        directResolves: Boolean,
        listResolves: Boolean,
    ): Screen? = when {
        sdkInt < 23 || exempt || batteryLess -> null
        directResolves -> Screen.Direct
        listResolves -> Screen.List
        else -> null
    }

    /**
     * Whether an engine start may ask once (when [screen] also finds a screen to open): only with
     * start on boot on, where background kills hurt most. TVs are battery-less, so they never ask.
     */
    fun shouldPrompt(alreadyShown: Boolean, startOnBoot: Boolean): Boolean = !alreadyShown && startOnBoot
}
