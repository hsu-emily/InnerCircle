package com.emilyhsu.innercircle.webview

import android.content.Context
import android.content.pm.ApplicationInfo
import com.emilyhsu.innercircle.data.SocialApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JavaScript string injected into every page: the generic engines from
 * assets/feed_cleaner.js (hiding, search, pager lock) and assets/usage_tracker.js (stats), each
 * with a JSON config generated from the selected platform's isolated selector file.
 */
object FeedCleaner {

    /** Must match TAG in the asset scripts. */
    const val LOG_PREFIX = "[InnerCircle]"

    private const val ENGINE_ASSET = "feed_cleaner.js"
    private const val TRACKER_ASSET = "usage_tracker.js"

    fun buildScript(context: Context, app: SocialApp): String {
        // Debug builds also write the story recorder's notes to Logcat (see feed_cleaner.js).
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val engine = context.assets.open(ENGINE_ASSET).bufferedReader().use { it.readText() }
        val tracker = context.assets.open(TRACKER_ASSET).bufferedReader().use { it.readText() }
        return buildString {
            // Each engine is guarded separately so one failing can't take the other down. The
            // try/catch only covers failures while an engine installs. A JS *syntax* error can't be
            // caught here, but it still reaches Logcat as an "Uncaught" console error. Errors thrown
            // later, from observers, are caught in the engines themselves.
            val selectors = selectorsFor(app)
            appendGuarded(engine, "__innerCircle.install(${configJson(debug, selectors)});", "injection failed")
            appendGuarded(tracker, "__icTracker.install(${trackingJson(selectors)});", "tracker injection failed")
        }
    }

    private fun StringBuilder.appendGuarded(engine: String, install: String, failure: String) {
        append("try {\n")
        append(engine)
        append("\n").append(install).append("\n")
        append("} catch (e) {\n")
        append("  console.error('").append(LOG_PREFIX).append(' ').append(failure).append(" :: ' + (e && e.stack || e));\n")
        append("}\n")
    }

    private fun selectorsFor(app: SocialApp): PlatformSelectorConfig = when (app) {
        SocialApp.Instagram -> InstagramSelectors.config
        SocialApp.YouTube -> YouTubeSelectors.config
        else -> error("No cleaner configuration for ${app.id}")
    }

    private fun trackingJson(selectors: PlatformSelectorConfig): String = selectors.tracking.let { rule ->
        JSONObject()
            .put("postSelector", rule.postSelector)
            .put("postKeySelector", rule.postKeySelector)
            .put("minPostHeight", rule.minPostHeight)
            .put("postDwellMs", rule.postDwellMs)
            .put("storyPathPattern", rule.storyPathPattern ?: JSONObject.NULL)
            .toString()
    }

    private fun configJson(debug: Boolean, selectors: PlatformSelectorConfig): String = JSONObject().apply {
        put("debug", debug)
        put("hideRules", JSONArray().apply {
            selectors.hideRules.forEach { rule ->
                put(
                    JSONObject()
                        .put("name", rule.name)
                        .put("selector", rule.selector)
                        .put("pathPattern", rule.pathPattern ?: JSONObject.NULL)
                        .put("keepLayoutBox", rule.keepLayoutBox)
                )
            }
        })
        put("explore", selectors.explore?.let { rule ->
            JSONObject()
                .put("explorePath", rule.explorePath)
                .put("searchPath", rule.searchPath)
                .put("searchInputSelector", rule.searchInputSelector)
                .put("tabSelector", rule.tabSelector)
        } ?: JSONObject.NULL)
        put("scrollLocks", JSONArray().apply {
            selectors.scrollLocks.forEach { rule ->
                put(
                    JSONObject()
                        .put("name", rule.name)
                        .put("pathPattern", rule.pathPattern ?: JSONObject.NULL)
                        .put("videoSelector", rule.videoSelector)
                        .put("minScrollRatio", rule.minScrollRatio)
                        .put("minVideoHeightRatio", rule.minVideoHeightRatio)
                )
            }
        })
        put("storyAds", selectors.storyAds?.let { rule ->
            JSONObject()
                .put("pathPattern", rule.pathPattern)
                .put("adLinkSelectors", JSONArray(rule.adLinkSelectors))
                .put("adTexts", JSONArray(rule.adTexts))
                .put("headerFraction", rule.headerFraction)
                .put("nextSelectors", JSONArray(rule.nextSelectors))
        } ?: JSONObject.NULL)
        put("textRules", JSONArray().apply {
            selectors.textRules.forEach { rule ->
                put(
                    JSONObject()
                        .put("name", rule.name)
                        .put("textSelector", rule.textSelector)
                        .put("texts", JSONArray(rule.texts))
                        .put("hideClosest", rule.hideClosest)
                        .put("keepLayoutBox", rule.keepLayoutBox)
                        .put("endOfFeed", rule.endOfFeed)
                )
            }
        })
        put("routeBlocks", JSONArray().apply {
            selectors.routeBlocks.forEach { rule ->
                put(JSONObject()
                    .put("name", rule.name)
                    .put("pathPattern", rule.pathPattern)
                    .put("destination", rule.destination)
                    .put("mediaSelector", rule.mediaSelector))
            }
        })
    }.toString()
}
