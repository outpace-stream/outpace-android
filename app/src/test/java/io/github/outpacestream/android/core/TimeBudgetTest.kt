package io.github.outpacestream.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeBudgetTest {
    private var clock = 0L
    private fun budget(total: Long) = TimeBudget(total) { clock }

    @Test fun capsEachRequest() = assertEquals(1_000, budget(1_500).next(capMs = 1_000, minUsefulMs = 100))

    @Test fun shrinksToWhatIsLeft() {
        val b = budget(1_500)
        clock = 1_200
        assertEquals(300, b.next(capMs = 1_000, minUsefulMs = 100))
    }

    @Test fun stopsWhenTooLittleIsLeft() {
        val b = budget(1_500)
        clock = 1_450
        assertNull(b.next(capMs = 1_000, minUsefulMs = 100))
    }

    @Test fun fourSlowRequestsStayWithinTheBudget() {
        val b = budget(1_500)
        var spent = 0L
        repeat(4) {
            val t = b.next(capMs = 1_000, minUsefulMs = 100) ?: return@repeat
            clock += t // each request runs to its timeout
            spent += t
        }
        assertEquals(1_500L, spent)
    }
}
