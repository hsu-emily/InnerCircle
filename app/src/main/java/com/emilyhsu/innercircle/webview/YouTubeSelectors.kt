package com.emilyhsu.innercircle.webview

/**
 * THE file to edit when YouTube changes its mobile DOM.
 *
 * These hooks were inspected on the live English mobile site (m.youtube.com, Sept 2026). In
 * particular, the bottom bar is a `ytm-pivot-bar-renderer` and its Shorts item is a
 * `ytm-pivot-bar-item-renderer` containing `.pivot-shorts`; do not substitute desktop selectors.
 * The cleaner logs each rule's match count to Logcat under `InnerCircleJS`.
 *
 * This session had an empty, signed-out feed and YouTube did not serve an ad. Consequently there
 * is deliberately no unverified ad selector here. Ads are documented as best-effort until a live
 * rendered ad can establish a stable marker.
 */
object YouTubeSelectors {

    val config = PlatformSelectorConfig(
        hideRules = listOf(
            // The exact rendered mobile nav shape: hiding the renderer removes both the icon and
            // its hit target instead of leaving an empty Shorts tab behind.
            HideRule(
                name = "shortsTab",
                selector = "ytm-pivot-bar-item-renderer:has([role='tab'].pivot-shorts)",
            ),
        ),
        textRules = emptyList(),
        explore = null,
        scrollLocks = emptyList(),
        storyAds = null,
        routeBlocks = listOf(
            // A Shorts player uses /shorts/<video-id>. Pause any media already created, then
            // replace the history entry before YouTube can page to the next Short.
            RouteBlockRule(
                name = "shortsPlayer",
                pathPattern = "^/shorts(?:/|$)",
                destination = "/feed/subscriptions?app=m&persist_app=1",
                mediaSelector = "#player-shorts-container video, video",
            ),
        ),
        // Native screen time is always recorded; video-card counts are best-effort until a
        // signed-in subscriptions feed can be rechecked after YouTube changes its renderer.
        tracking = TrackingRule(
            postSelector = "ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer",
            postKeySelector = "a[href^='/watch']",
            minPostHeight = 120,
            postDwellMs = 1000,
            storyPathPattern = null,
        ),
    )
}
