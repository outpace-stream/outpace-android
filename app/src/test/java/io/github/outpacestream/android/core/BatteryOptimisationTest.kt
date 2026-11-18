package io.github.outpacestream.android.core

import io.github.outpacestream.android.core.BatteryOptimisation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimisationTest {
    @Test fun tvIsBatteryLessWhateverTheBroadcastSays() {
        assertTrue(BatteryOptimisation.batteryLess(isTv = true, batteryPresent = true))
        assertTrue(BatteryOptimisation.batteryLess(isTv = true, batteryPresent = null))
    }

    @Test fun missingBatteryIsBatteryLess() {
        assertTrue(BatteryOptimisation.batteryLess(isTv = false, batteryPresent = false))
    }

    @Test fun phoneWithBatteryOrUnknownBroadcastHasBattery() {
        assertFalse(BatteryOptimisation.batteryLess(isTv = false, batteryPresent = true))
        assertFalse(BatteryOptimisation.batteryLess(isTv = false, batteryPresent = null))
    }

    private fun screen(
        sdkInt: Int = 34,
        exempt: Boolean = false,
        batteryLess: Boolean = false,
        direct: Boolean = true,
        list: Boolean = true,
    ) = BatteryOptimisation.screen(sdkInt, exempt, batteryLess, direct, list)

    @Test fun phonePrefersDirectPrompt() {
        assertEquals(Screen.Direct, screen())
    }

    @Test fun fallsBackToListWhenDirectIsMissing() {
        assertEquals(Screen.List, screen(direct = false))
    }

    @Test fun hiddenWhenNeitherScreenResolves() {
        assertNull(screen(direct = false, list = false))
    }

    @Test fun hiddenOnBatteryLessDevices() {
        assertNull(screen(batteryLess = true))
    }

    @Test fun hiddenWhenExemptOrBeforeDoze() {
        assertNull(screen(exempt = true))
        assertNull(screen(sdkInt = 22))
    }

    @Test fun promptsOnceOnlyWithStartOnBoot() {
        assertTrue(BatteryOptimisation.shouldPrompt(alreadyShown = false, startOnBoot = true))
        assertFalse(BatteryOptimisation.shouldPrompt(alreadyShown = true, startOnBoot = true))
        assertFalse(BatteryOptimisation.shouldPrompt(alreadyShown = false, startOnBoot = false))
    }
}
