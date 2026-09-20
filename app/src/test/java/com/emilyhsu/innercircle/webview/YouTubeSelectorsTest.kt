package com.emilyhsu.innercircle.webview

import com.emilyhsu.innercircle.data.SocialApp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSelectorsTest {

    @Test fun startsOnSubscriptionsRatherThanTheRecommendedHomeFeed() {
        assertTrue(SocialApp.YouTube.enabled)
        assertTrue(SocialApp.YouTube.startUrl!!.startsWith("https://m.youtube.com/feed/subscriptions"))
    }

    @Test fun shortsRulesOnlyTargetTheRenderedMobileHooks() {
        assertEquals(
            "ytm-pivot-bar-item-renderer:has([role='tab'].pivot-shorts)",
            YouTubeSelectors.hideRules.single { it.name == "shortsTab" }.selector,
        )
        val block = YouTubeSelectors.routeRedirects.single { it.name == "shortsPlayer" }
        assertEquals("^/shorts(?:/|$)", block.fromPattern)
        assertTrue(block.to.startsWith("/feed/subscriptions"))
        assertNotNull("a Short must be paused before the page leaves it", block.pauseMediaSelector)
    }

    @Test fun adsAreNotPretendedToBeSupportedWithoutALiveMarker() {
        assertTrue(YouTubeSelectors.textRules.isEmpty())
        assertNull(YouTubeSelectors.storyAds)
        assertFalse(YouTubeSelectors.hideRules.any { "ad" in it.name.lowercase() })
    }

    @Test fun theBridgeAcceptsBothOfYouTubesFirstPartyHosts() {
        assertEquals(setOf("https://m.youtube.com", "https://www.youtube.com"), YouTubeSelectors.bridgeOrigins)
        // Instagram and TikTok keep the single-origin default.
        assertEquals(setOf(InstagramSelectors.origin), InstagramSelectors.bridgeOrigins)
    }

    @Test fun thePauseSelectorReachesTheEngine() {
        val config = JSONObject(FeedCleaner.configJson(YouTubeSelectors, debug = false))
        val redirect = config.getJSONArray("routeRedirects").getJSONObject(0)
        assertEquals("#player-shorts-container video, video", redirect.getString("pauseMediaSelector"))
    }
}
