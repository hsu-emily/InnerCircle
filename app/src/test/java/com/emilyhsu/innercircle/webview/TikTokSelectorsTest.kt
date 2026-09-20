package com.emilyhsu.innercircle.webview

import com.emilyhsu.innercircle.data.SocialApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikTok's side of the rules: which routes get pulled back to the Following feed, and which slides
 * get covered on the way. The regexes here run in the browser, but Kotlin and JS agree on the small
 * subset used, so testing them on the JVM says something real about what the page will do.
 */
class TikTokSelectorsTest {

    @Test fun theAppOpensOnTheFollowingFeed() {
        assertEquals("https://www.tiktok.com/following", SocialApp.TikTok.startUrl)
        assertTrue(SocialApp.TikTok.enabled)
    }

    @Test fun theAlgorithmicRoutesAreRedirected() {
        listOf("/", "/foryou", "/foryou/", "/explore", "/explore/trending", "/discover/food")
            .forEach { path -> assertTrue(path, redirectFor(path) != null) }
    }

    @Test fun theFollowingFeedAndRealPagesAreLeftAlone() {
        // A redirect off any of these would take the user away from content they asked for.
        listOf("/following", "/@someone", "/@someone/video/123", "/search?q=x", "/music/song-1", "/tag/cats")
            .forEach { path -> assertEquals(path, null, redirectFor(path)) }
    }

    @Test fun redirectsGiveUpSoTheyCannotVolleyWithTikTokForever() {
        // Logged out, TikTok answers /following by sending the user back to /foryou. Without a cap
        // the engine would answer that with another /following and reload the page indefinitely.
        TikTokSelectors.routeRedirects.forEach { rule ->
            assertTrue(rule.name, rule.maxPerSession in 1..5)
        }
    }

    @Test fun theForYouFallbackOnlyAppliesWhereTheRedirectFailed() {
        val forYou = slideRule("forYouFeed")
        assertEquals(
            "the fallback must cover exactly the route the redirect is trying to leave",
            TikTokSelectors.routeRedirects.first().fromPattern,
            forYou.pathPattern,
        )
        assertTrue("covers the whole route, so it must carry no markers", forYou.markerSelectors.isEmpty())
        assertTrue(forYou.markerTexts.isEmpty())
        // The rule's own route is the one the redirect is trying to leave, and never /following.
        assertFalse(Regex(forYou.pathPattern!!).containsMatchIn("/following"))
    }

    @Test fun adAndSuggestionRulesOnlyFireOnAMarker() {
        // These run on every route, including /following, so a marker-less version of either would
        // blank out the videos the app exists to keep.
        listOf(slideRule("sponsoredSlides"), slideRule("suggestedSlides")).forEach { rule ->
            assertEquals(rule.name, null, rule.pathPattern)
            assertTrue(rule.name, rule.markerSelectors.isNotEmpty() && rule.markerTexts.isNotEmpty())
        }
    }

    @Test fun theOffSiteLinkMarkerDoesNotCatchTikToksOwnLinks() {
        // Organic slides link to /@user, /music/..., /tag/... — all same-origin. The marker looks
        // for a link out of tiktok.com, which is what an ad's call to action is.
        val marker = slideRule("sponsoredSlides").markerSelectors.single { "target='_blank'" in it }
        assertTrue(marker, ":not([href*='tiktok.com'])" in marker)
    }

    @Test fun everySlideRuleSaysWhyTheSlideIsGone() {
        // A blank cover just looks like TikTok broke.
        TikTokSelectors.slideRules.forEach { rule -> assertTrue(rule.name, rule.label.isNotBlank()) }
    }

    @Test fun everySlideRuleTargetsThePagerSlideItself() {
        // Covering an inner element instead would leave the video playing around it.
        TikTokSelectors.slideRules.forEach { rule ->
            assertEquals(rule.name, "[data-e2e^='video-slide']", rule.slideSelector)
        }
    }

    @Test fun trackingCountsVideosByCoverImageBecauseSlidesAreRecycled() {
        val tracking = TikTokSelectors.tracking
        assertEquals("[data-e2e^='video-slide']", tracking.postSelector)
        assertEquals("src", tracking.postKeyAttr)
        assertEquals("", tracking.storyPathPattern) // no story viewer on TikTok's mobile web
    }

    private fun redirectFor(path: String): RouteRedirect? =
        TikTokSelectors.routeRedirects.firstOrNull {
            path != it.to && Regex(it.fromPattern).containsMatchIn(path)
        }

    private fun slideRule(name: String): SlideRule = TikTokSelectors.slideRules.single { it.name == name }
}
