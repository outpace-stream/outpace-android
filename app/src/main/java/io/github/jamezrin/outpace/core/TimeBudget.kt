package io.github.jamezrin.outpace.core

/**
 * A shared time budget for a batch of optional requests. Each request gets the smaller of its own
 * cap and what is left, and none starts once too little is left to be useful.
 */
class TimeBudget(totalMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val deadline = now() + totalMs

    /** The timeout for the next request, or null when the budget is spent. */
    fun next(capMs: Int, minUsefulMs: Int): Int? {
        val left = deadline - now()
        if (left < minUsefulMs) return null
        return minOf(capMs.toLong(), left).toInt()
    }
}
