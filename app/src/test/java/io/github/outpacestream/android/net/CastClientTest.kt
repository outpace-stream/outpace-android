package io.github.outpacestream.android.net

import org.junit.Assert.assertTrue
import org.junit.Test

class CastClientTest {
    /** `play` must outlast engine 1.2.0's slowest valid sequence, or an accepted cast goes untracked. */
    @Test fun playDeadlineCoversTheEngineWorstCase() {
        assertTrue(CastClient.ENGINE_PLAY_WORST_MS >= 50_000)
        assertTrue(CastClient.PLAY_MS >= CastClient.ENGINE_PLAY_WORST_MS + 20_000)
    }
}
