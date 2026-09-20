package com.emilyhsu.innercircle.webview

import com.emilyhsu.innercircle.data.SocialApp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a platform's selector file has to keep to. These can't tell whether a selector still
 * matches the live site (only inspecting the page can), but they do catch a platform that is
 * switched on with nothing behind it, a rule that would hide the wrong thing everywhere, and a
 * config the JS engine wouldn't understand.
 */
class PlatformSelectorsTest {

    @Test fun everyAppTheUserCanOpenHasSelectorsAndSomewhereToOpen() {
        SocialApp.entries.filter { it.enabled }.forEach { app ->
            assertNotNull("${app.displayName} is enabled but has no selectors", selectorsFor(app))
            assertNotNull("${app.displayName} is enabled but has no start URL", app.startUrl)
        }
    }

    @Test fun anAppThatIsntBuiltYetHasNoSelectors() {
        assertNull(selectorsFor(SocialApp.Facebook))
        assertNull(selectorsFor(SocialApp.LinkedIn))
    }

    @Test fun eachAppStartsOnItsOwnOrigin() {
        // The page bridge only listens to the platform's origin, so a start URL somewhere else
        // would silently lose every usage count.
        SocialApp.entries.filter { it.enabled }.forEach { app ->
            val selectors = selectorsFor(app) ?: return@forEach
            assertTrue(
                "${app.displayName} starts at ${app.startUrl}, outside ${selectors.origin}",
                app.startUrl.orEmpty().startsWith(selectors.origin + "/"),
            )
        }
    }

    @Test fun noRuleLeansOnAGeneratedClassName() {
        // Instagram's and TikTok's class names are hashes that change between deploys.
        platforms().forEach { selectors ->
            (selectors.hideRules.map { it.selector } +
                selectors.textRules.map { it.textSelector } +
                selectors.slideRules.map { it.slideSelector } +
                selectors.slideRules.flatMap { it.markerSelectors }).forEach { selector ->
                assertFalse("${selectors.displayName} depends on a generated class: $selector", "css-" in selector)
            }
        }
    }

    @Test fun aSlideRuleWithNoMarkersIsPinnedToARoute() {
        // Such a rule covers every slide it sees. Loose on the whole site, it would blank out
        // profiles and anything else built out of the same pager.
        platforms().forEach { selectors ->
            selectors.slideRules.filter { it.markerSelectors.isEmpty() && it.markerTexts.isEmpty() }.forEach { rule ->
                assertNotNull("${rule.name} covers every slide but isn't limited to a route", rule.pathPattern)
            }
        }
    }

    @Test fun ruleNamesAreUniquePerPlatform() {
        // The engine logs match counts per name and marks covered slides with it.
        platforms().forEach { selectors ->
            val names = selectors.hideRules.map { it.name } + selectors.textRules.map { it.name } +
                selectors.slideRules.map { it.name } + selectors.routeRedirects.map { it.name }
            assertEquals("${selectors.displayName} reuses a rule name: $names", names.size, names.toSet().size)
        }
    }

    @Test fun aRedirectNeverPointsAtARouteItWouldCatchAgain() {
        // Otherwise the engine would redirect the page it just arrived at, over and over, until
        // the per-session cap stopped it.
        platforms().forEach { selectors ->
            selectors.routeRedirects.forEach { rule ->
                assertFalse(
                    "${rule.name} sends ${rule.to} to itself",
                    Regex(rule.fromPattern).containsMatchIn(rule.to),
                )
            }
        }
    }

    @Test fun theConfigHandedToTheEngineCarriesEveryRule() {
        platforms().forEach { selectors ->
            val config = JSONObject(FeedCleaner.configJson(selectors, debug = false))
            assertEquals(selectors.hideRules.size, config.getJSONArray("hideRules").length())
            assertEquals(selectors.textRules.size, config.getJSONArray("textRules").length())
            assertEquals(selectors.slideRules.size, config.getJSONArray("slideRules").length())
            assertEquals(selectors.routeRedirects.size, config.getJSONArray("routeRedirects").length())
            assertEquals(selectors.scrollLocks.size, config.getJSONArray("scrollLocks").length())
            assertEquals(selectors.explore != null, config.has("explore"))
            assertEquals(selectors.storyAds != null, config.has("storyAds"))
        }
    }

    @Test fun trackingConfigNamesTheAttributeThatTellsPostsApart() {
        platforms().forEach { selectors ->
            val tracking = JSONObject(FeedCleaner.trackingJson(selectors))
            assertTrue(selectors.displayName, tracking.getString("postKeyAttr").isNotBlank())
            assertTrue(selectors.displayName, tracking.getString("postSelector").isNotBlank())
        }
    }

    @Test fun loadErrorsNameThePlatformTheUserWasOn() {
        assertTrue("TikTok" in describeLoadError(android.webkit.WebViewClient.ERROR_UNKNOWN, "TikTok"))
    }

    private fun platforms() = SocialApp.entries.mapNotNull { selectorsFor(it) }
}
