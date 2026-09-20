package com.emilyhsu.innercircle.webview

/**
 * THE file to edit when Instagram changes its DOM.
 *
 * Nothing else in the project knows what a Reels tab, an ad or the search page looks like; the
 * WebView setup and the JS engine (assets/feed_cleaner.js) just consume the rules below.
 *
 * Workflow: open chrome://inspect on your desktop, inspect the live page, prove a selector in the
 * DevTools console (`document.querySelectorAll("...")`), then paste it here and rebuild. The engine
 * logs "[InnerCircle] <rule name>: N matches" to Logcat (tag InnerCircleJS), so a rule that logs
 * 0 matches on a screen where it should match is stale.
 *
 * Selectors below were checked against the live logged-in mobile site (English, Sept 2026).
 * Anything marked FRAGILE is the first place to look when something stops working.
 */
object InstagramSelectors {

    val config: PlatformSelectorConfig
        get() = PlatformSelectorConfig(hideRules, textRules, explore, scrollLocks, storyAds, tracking)

    /**
     * Rules that can be expressed as a plain CSS selector. They're injected as a <style> tag
     * (`display: none !important`), so newly loaded content is hidden before it ever paints.
     *
     * Prefer stable hooks (href, aria-label, role, data-* attributes) over class names. Instagram's
     * classes are generated and change between deploys.
     */
    val hideRules: List<HideRule> = listOf(
        // Bottom-nav Reels tab. FRAGILE if it stops working: hiding only the <a> can leave a gap in
        // the tab bar; hide its wrapper instead, e.g. "div:has(> a[href='/reels/'])".
        HideRule(
            name = "reelsTab",
            selector = "a[href='/reels/']",
        ),

        // The "Original audio" link on reel posts. It opens /reels/audio/<id>/, another page of
        // endless reels, so it's hidden on purpose rather than left as a way around the pager lock.
        HideRule(
            name = "reelsAudioLinks",
            selector = "a[href^='/reels/audio/']",
        ),

        // Ads. Every ad post links out through facebook.com/ads/ig_redirect/..., and no organic post
        // does, so this doesn't depend on the "Ad" label text or the account's language.
        // keepLayoutBox: these are items in Instagram's virtualized feed; see HideRule.
        HideRule(
            name = "adPosts",
            selector = "article:has(a[href*='/ads/'])",
            keepLayoutBox = true,
        ),

        // The Explore grid (the "feed" of the Search tab). Only hidden on the Explore route itself;
        // the search view is /explore/search/ and is untouched. Normally you never see this because
        // the engine sends you straight to search (see `explore` below), this is the safety net.
        HideRule(
            name = "exploreGrid",
            selector = "main a[href^='/p/'], main a[href^='/reel/']",
            pathPattern = EXPLORE_PATH,
        ),
    )

    /**
     * Rules that need to match on *text* (CSS can't), then hide an ancestor container. The engine
     * re-runs these from a MutationObserver as the feed loads more content.
     *
     * Matching is exact (trimmed, case-insensitive) on leaf elements only, so a normal post whose
     * caption happens to contain the word "Ad" won't be hidden.
     *
     * The text is locale-dependent. This assumes the account/browser language is English.
     */
    val textRules: List<TextRule> = listOf(
        // Backup for adPosts above, in case an ad ever appears without the facebook.com/ads link.
        // The mobile-web label is "Ad" (older/desktop UI says "Sponsored").
        TextRule(
            name = "sponsoredLabel",
            textSelector = "article span",
            texts = listOf("Ad", "Sponsored"),
            hideClosest = "article",
            keepLayoutBox = true,
        ),

        // Suggested posts carry this label inside their <article>.
        TextRule(
            name = "suggestedForYou",
            textSelector = "article span",
            texts = listOf("Suggested for you", "Suggested posts"),
            hideClosest = "article",
            keepLayoutBox = true,
        ),

        // Once you've seen everything new, Instagram prints "You're all caught up" and then carries on
        // with a "Suggested Posts" section. Those posts have no label at all (just a Follow button), so
        // the rules above can't see them, and hiding them one by one makes Instagram keep loading more
        // forever (measured: 18 requests in 9 seconds and the page growing from 15 to 87 posts). So this
        // block is treated as the END of the feed: everything after it is made invisible but left in
        // place, and the page can't be scrolled past it. See `endOfFeed` in assets/feed_cleaner.js.
        // FRAGILE: the marker's wording (English) and that it is a <span> in the feed column.
        TextRule(
            name = "endOfFeed",
            textSelector = "span",
            texts = listOf("You're all caught up", "You\u2019re all caught up"),
            hideClosest = "span",
            endOfFeed = true,
        ),

        // The "Suggested Posts" heading inside that same block.
        TextRule(
            name = "suggestedPostsHeading",
            textSelector = "span",
            texts = listOf("Suggested Posts"),
            hideClosest = "span",
        ),
    )

    /**
     * Search tab behavior. The Explore route is never shown: arriving on it focuses the search box,
     * which makes Instagram open the search view with your recent searches. Cancel/Back from the
     * search view are sent back to wherever you came from instead of re-opening search.
     */
    val explore: ExploreRule = ExploreRule(
        explorePath = EXPLORE_PATH,
        searchPath = "^/explore/search",
        // FRAGILE: the search box on the Explore page.
        searchInputSelector = "input[type='search']",
        // The bottom-bar Search tab. Tapping it raises a page-coloured cover until the search view
        // is showing, so you don't see Instagram's route transition (see feed_cleaner.js).
        tabSelector = "a[href='/explore/']",
    )

    /**
     * Freezes reel pagers so only the reel you opened is reachable. Reels are paged in two places:
     * the `/reels/<code>/` route, and a full-screen overlay when you open a reel from a DM (the URL
     * stays `/direct/t/<id>/`). `/reel/<code>/` and grid taps are plain single-post pages.
     *
     * Both have obfuscated class names, so there is no selector for them. The engine recognises the
     * *shape* instead: a scrolling container much taller than its box, holding a near-full-height
     * video (see [ScrollLockRule]). FRAGILE: if the lock stops working, check in DevTools that the
     * pager still contains a `<video>` nearly as tall as the pager's visible height.
     */
    val scrollLocks: List<ScrollLockRule> = listOf(
        ScrollLockRule(name = "reelPager"),
    )

    /**
     * Ads inside the story viewer. They're separate story items in the tray, so there's no element to
     * hide: the engine lays a blank black frame over the viewer the moment it recognises the ad (so the
     * ad is never seen), taps forward to the next story, and lifts the frame once the ad has gone.
     *
     * NOT YET CHECKED AGAINST A LIVE AD. Story ads only reach logged-in accounts, so these signals come
     * from how feed ads are marked plus how the viewer is usually laid out. When you next see a story
     * ad, inspect it (chrome://inspect) and adjust [StoryAdRule.adLinkSelectors], [StoryAdRule.adTexts]
     * and [StoryAdRule.nextSelectors]. Logcat shows "storyAd: ..." lines when the rule fires or gives up.
     */
    val storyAds: StoryAdRule = StoryAdRule(
        pathPattern = "^/stories/",
        // Feed ads all link out via facebook.com/ads/ig_redirect/...; story ads are expected to do the same.
        adLinkSelectors = listOf("a[href*='/ads/']"),
        // The "Ad" (or "Sponsored") tag printed under the account name in the top-left corner. It only
        // counts in the top of the screen ([headerFraction]).
        adTexts = listOf("Ad", "Sponsored"),
        headerFraction = 0.3,
        nextSelectors = listOf("button[aria-label='Next']", "[role='button'][aria-label='Next']", "[aria-label='Next']"),
    )

    /**
     * What the usage tracker (assets/usage_tracker.js) counts for the Stats screen. Posts are feed
     * `article`s that were mostly on screen for [TrackingRule.postDwellMs]; stories are distinct ids
     * seen in the story route. FRAGILE: if a stat reads 0 while you're clearly using the app, check
     * the story route and that feed posts are still `article` elements.
     */
    val tracking: TrackingRule = TrackingRule(
        postSelector = "article",
        // A permalink inside the post, used so a post scrolled back to isn't counted twice.
        postKeySelector = "a[href^='/p/'], a[href^='/reel/']",
        minPostHeight = 120,
        postDwellMs = 1000,
        storyPathPattern = "^/stories/[^/]+/(\\d+)",
    )

    private const val EXPLORE_PATH = "^/explore/?$"
}

/**
 * Hide every element matching [selector]. [name] only shows up in Logcat. When [pathPattern] (a JS
 * regex tested against `location.pathname`) is set, the rule only applies on matching routes.
 *
 * [keepLayoutBox]: collapse to zero height instead of `display: none`. Set this for posts in
 * Instagram's feed. The feed is virtualized and depends on every post keeping a box (its observers
 * never fire for `display: none` elements), so removing posts outright makes it stop loading and go
 * blank after some scrolling. Leave it false for anything that isn't a feed item.
 */
data class HideRule(
    val name: String,
    val selector: String,
    val pathPattern: String? = null,
    val keepLayoutBox: Boolean = false,
)

/**
 * Find leaf elements matching [textSelector] whose whole text equals one of [texts], then hide
 * their nearest ancestor matching [hideClosest] (passed to `Element.closest()`). See [HideRule] for
 * [keepLayoutBox].
 */
data class TextRule(
    val name: String,
    val textSelector: String,
    val texts: List<String>,
    val hideClosest: String,
    val keepLayoutBox: Boolean = false,
    /** Not hidden: marks the end of the feed. Everything after it is hidden and scrolling stops there. */
    val endOfFeed: Boolean = false,
)

/**
 * See [InstagramSelectors.storyAds]. A story counts as an ad when, on a route matching [pathPattern], a
 * visible element (outside any feed `article`) matches one of [adLinkSelectors], or a visible leaf
 * element in the top [headerFraction] of the screen has exactly one of [adTexts] as its text. To move
 * on, the engine asks the app for a real tap on the right side of the screen (see NativeTap.kt), then tries
 * the first visible [nextSelectors] match, then the right-arrow key with a scripted tap.
 */
data class StoryAdRule(
    val pathPattern: String,
    val adLinkSelectors: List<String>,
    val adTexts: List<String>,
    val headerFraction: Double,
    val nextSelectors: List<String>,
)

/**
 * See [InstagramSelectors.tracking]. [storyPathPattern] is a JS regex whose first capture group is
 * the story id.
 */
data class TrackingRule(
    val postSelector: String,
    val postKeySelector: String,
    val minPostHeight: Int,
    val postDwellMs: Int,
    val storyPathPattern: String?,
)

/** A route which must never remain visible, such as a full-screen endless-video player. */
data class RouteBlockRule(
    val name: String,
    val pathPattern: String,
    val destination: String,
    /** Any media already mounted is paused before navigation begins. */
    val mediaSelector: String = "video",
)

/** Platform-owned rules consumed by the shared page cleaner and tracker. */
data class PlatformSelectorConfig(
    val hideRules: List<HideRule>,
    val textRules: List<TextRule>,
    val explore: ExploreRule?,
    val scrollLocks: List<ScrollLockRule>,
    val storyAds: StoryAdRule?,
    val tracking: TrackingRule,
    val routeBlocks: List<RouteBlockRule> = emptyList(),
)

/** See [InstagramSelectors.explore]. Paths are JS regexes tested against `location.pathname`. */
data class ExploreRule(
    val explorePath: String,
    val searchPath: String,
    val searchInputSelector: String,
    val tabSelector: String,
)

/**
 * See [InstagramSelectors.scrollLocks]. A pager is a scroller (overflow auto/scroll) whose content is
 * at least [minScrollRatio] times taller than its visible box, containing a [videoSelector] element
 * at least [minVideoHeightRatio] as tall as that box. Feed and chat videos are far shorter than the
 * box of a full-screen reel, which is what keeps this from matching ordinary scrolling lists.
 * [pathPattern] optionally limits the rule to routes matching a JS regex; null means everywhere.
 */
data class ScrollLockRule(
    val name: String,
    val pathPattern: String? = null,
    val videoSelector: String = "video",
    val minScrollRatio: Double = 1.8,
    val minVideoHeightRatio: Double = 0.7,
)
