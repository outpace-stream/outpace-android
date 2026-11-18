package io.github.outpacestream.android.core

import io.github.outpacestream.android.core.RecommendedPlayer.VLC_PACKAGE
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendedPlayerTest {
    private val stock = "com.example.stockplayer"
    private val other = "com.example.otherplayer"

    @Test fun vlcInstalledIsOfferedWithoutHint() =
        assertEquals(PlayerOffer(vlc = 1, hint = false, storeLink = false), RecommendedPlayer.offer(listOf(stock, VLC_PACKAGE), hintDismissed = false, storeAvailable = true))

    @Test fun vlcInstalledIgnoresDismissal() =
        assertEquals(PlayerOffer(vlc = 0, hint = false, storeLink = false), RecommendedPlayer.offer(listOf(VLC_PACKAGE), hintDismissed = true, storeAvailable = false))

    @Test fun vlcMissingShowsHintWithStore() =
        assertEquals(PlayerOffer(vlc = null, hint = true, storeLink = true), RecommendedPlayer.offer(listOf(stock, other), hintDismissed = false, storeAvailable = true))

    @Test fun vlcMissingWithoutStoreShowsHintOnly() =
        assertEquals(PlayerOffer(vlc = null, hint = true, storeLink = false), RecommendedPlayer.offer(listOf(stock), hintDismissed = false, storeAvailable = false))

    @Test fun dismissedHintStaysHidden() =
        assertEquals(PlayerOffer(vlc = null, hint = false, storeLink = false), RecommendedPlayer.offer(listOf(stock), hintDismissed = true, storeAvailable = true))

    @Test fun noPlayersStillHints() =
        assertEquals(PlayerOffer(vlc = null, hint = true, storeLink = true), RecommendedPlayer.offer(emptyList(), hintDismissed = false, storeAvailable = true))

    @Test fun similarPackageIsNotVlc() =
        assertEquals(null, RecommendedPlayer.offer(listOf("$VLC_PACKAGE.fake"), hintDismissed = false, storeAvailable = false).vlc)

    @Test fun vlcFirstKeepsOtherOrder() {
        assertEquals(listOf(2, 0, 1, 3), RecommendedPlayer.vlcFirst(listOf(stock, other, VLC_PACKAGE, "com.example.third")))
        assertEquals(listOf(0, 1), RecommendedPlayer.vlcFirst(listOf(stock, other)))
        assertEquals(emptyList<Int>(), RecommendedPlayer.vlcFirst(emptyList()))
    }

    @Test fun storeUriNamesVlc() = assertEquals("market://details?id=org.videolan.vlc", RecommendedPlayer.STORE_URI)
}
