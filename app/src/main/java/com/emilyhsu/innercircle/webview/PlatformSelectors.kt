package com.emilyhsu.innercircle.webview

import com.emilyhsu.innercircle.data.SocialApp

/**
 * What one platform's selector file has to provide. [InstagramSelectors] and [TikTokSelectors] are
 * the two implementations; everything else (the WebView setup, [FeedCleaner], the JS engines) only
 * ever sees this interface, so adding a platform stays a one-file job.
 *
 * Rules a platform doesn't need are left at their defaults below.
 */
interface PlatformSelectors {

    /** Origin the page bridge is limited to, e.g. "https://www.instagram.com". */
    val origin: String

    /** Name used in the "couldn't load" messages. */
    val displayName: String

    val hideRules: List<HideRule>
    val textRules: List<TextRule>
    val tracking: TrackingRule

    val explore: ExploreRule? get() = null
    val scrollLocks: List<ScrollLockRule> get() = emptyList()
    val storyAds: StoryAdRule? get() = null
    val routeRedirects: List<RouteRedirect> get() = emptyList()
    val slideRules: List<SlideRule> get() = emptyList()
}

/** The selectors for [app], or null for the platforms that aren't built yet. */
fun selectorsFor(app: SocialApp): PlatformSelectors? = when (app) {
    SocialApp.Instagram -> InstagramSelectors
    SocialApp.TikTok -> TikTokSelectors
    else -> null
}

/**
 * Send one route to another one. [fromPattern] is a JS regex tested against `location.pathname`;
 * [to] is the path to go to instead, with `location.replace` so Back doesn't come straight back.
 *
 * The engine gives up after [maxPerSession] redirects in quick succession (counted in
 * sessionStorage, since each redirect is a fresh document) and logs why: if the platform ever
 * bounces [to] back to a matching route, the alternative is an endless reload loop.
 */
data class RouteRedirect(
    val name: String,
    val fromPattern: String,
    val to: String,
    val maxPerSession: Int = 3,
)

/**
 * Hiding for feed items that live in a *pager* (TikTok's swipeable full-screen video feed) rather
 * than in a scrolling list.
 *
 * Neither way of hiding an ordinary feed item works there. `display: none` takes the slide out of
 * the pager, and collapsing it to zero height (what Instagram's feed needs, see [HideRule]) leaves
 * the pager's own geometry behind: measured on www.tiktok.com, collapsing one of two slides left
 * the wrapper translating by a full viewport height for a slide that was now 0px tall, so the feed
 * scrolled to blank space. So a matched slide keeps its box and its place, and instead gets an
 * opaque cover laid over it while its video is paused and muted: nothing of the item is seen or
 * heard, the pager still works, and nothing asks the platform for more items.
 *
 * A slide matches when it contains a visible element matching one of [markerSelectors], or a leaf
 * element whose whole text is one of [markerTexts] (trimmed, case-insensitive). A rule
 * with neither covers every slide it sees, which is only sensible together with [pathPattern]:
 * that pair is how a whole algorithmic route gets blocked while the rest of the page stays usable.
 */
data class SlideRule(
    val name: String,
    val slideSelector: String,
    val markerSelectors: List<String> = emptyList(),
    val markerTexts: List<String> = emptyList(),
    /** JS regex tested against `location.pathname`; null means every route. */
    val pathPattern: String? = null,
    val videoSelector: String = "video",
    /** Shown in the middle of the cover, so a hidden item doesn't look like a broken one. */
    val label: String = "",
)
