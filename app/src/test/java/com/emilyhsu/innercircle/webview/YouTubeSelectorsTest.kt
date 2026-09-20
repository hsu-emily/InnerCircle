package com.emilyhsu.innercircle.webview

import com.emilyhsu.innercircle.data.SocialApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSelectorsTest {

    @Test fun startsOnSubscriptionsRatherThanTheRecommendedHomeFeed() {
        assertTrue(SocialApp.YouTube.enabled)
        assertTrue(SocialApp.YouTube.startUrl!!.startsWith("https://m.youtube.com/feed/subscriptions"))
    }

    @Test fun shortsRulesOnlyTargetTheRenderedMobileHooks() {
        val rules = YouTubeSelectors.config
        assertEquals(
            "ytm-pivot-bar-item-renderer:has([role='tab'].pivot-shorts)",
            rules.hideRules.single { it.name == "shortsTab" }.selector,
        )
        val block = rules.routeBlocks.single { it.name == "shortsPlayer" }
        assertEquals("^/shorts(?:/|$)", block.pathPattern)
        assertTrue(block.destination.startsWith("/feed/subscriptions"))
    }

    @Test fun adsAreNotPretendedToBeSupportedWithoutALiveMarker() {
        val rules = YouTubeSelectors.config
        assertTrue(rules.textRules.isEmpty())
        assertTrue(rules.storyAds == null)
        assertFalse(rules.hideRules.any { "ad" in it.name.lowercase() })
    }
}
