package com.emilyhsu.innercircle.webview

/**
 * THE file to edit when TikTok changes its DOM. Same job as [InstagramSelectors], same workflow:
 * prove a selector in DevTools against the live page (chrome://inspect), then paste it here. The
 * engine logs "[InnerCircle] <rule name>: N matches" to Logcat (tag InnerCircleJS), so a rule
 * logging 0 matches where it should match is stale.
 *
 * TikTok's class names are generated hashes ("css-1ssqwx2-f59ed85f--DivVideoSlideContainer") and
 * change between deploys, so every selector below uses a `data-e2e` attribute, an href, or the
 * shape of the page instead. Each one was read off the live mobile site (www.tiktok.com, phone
 * emulation, logged out, Sept 2026) rather than from memory.
 *
 * The blocking strategy, and why:
 *
 *  - TikTok has no "algorithmic tab" to hide the way Instagram has a Reels tab: `/` redirects to
 *    `/foryou`, which *is* the algorithm. The only feed made of accounts you chose is `/following`,
 *    a real route on the mobile site. So the app starts there ([com.emilyhsu.innercircle.data.SocialApp.TikTok]'s
 *    startUrl) and [routeRedirects] sends `/`, `/foryou` and `/explore` back to it, which also
 *    covers the bottom-bar Home tab and any in-page link into the For You feed.
 *  - Inside the Following feed, anything that isn't from an account you follow (ads, suggested
 *    videos) is a *slide in a pager*, which can't be hidden with CSS without breaking the pager.
 *    See [SlideRule] for what the engine does instead, and the measurement behind it.
 *
 * What is NOT verified, and is therefore best-effort (see also the README):
 *  - [slideRules]: TikTok's logged-out mobile web stops after two videos and shows an app-install
 *    interstitial, so no ad and no suggested slide could be loaded to inspect. The markers below
 *    are the ones TikTok's own web UI uses for ads elsewhere plus the labels the app shows; treat
 *    them as unproven and re-inspect the first time a sponsored video slips through.
 *  - Whether the Following feed's pager virtualizes its slides with more than a handful loaded. On
 *    the logged-out feed the Swiper instance reported `virtual: false` with 2 slides. The cover
 *    approach in [SlideRule] is safe either way, which is part of why it was chosen.
 */
object TikTokSelectors : PlatformSelectors {

    override val origin = "https://www.tiktok.com"
    override val displayName = "TikTok"

    /**
     * Plain-CSS rules, injected as a <style> tag, so matching content never paints.
     *
     * None of these are slides in the video pager: they're chrome and list items, which is why
     * `display: none` is safe here (see [slideRules] for the pager).
     */
    override val hideRules: List<HideRule> = listOf(
        // "Suggested accounts" — TikTok's who-to-follow block. Verified on /following while logged
        // out, where the whole page is the login prompt plus 39 of these cards; the same markers
        // are used for the suggestion strips shown between videos.
        HideRule(
            name = "suggestedAccounts",
            selector = "[data-e2e='suggest-accounts'], [data-e2e='suggest-card']",
        ),

        // Bottom-bar Discover tab: TikTok's trending/algorithmic browse surface. The search icon in
        // the header (a[href='/search']) is left alone, so looking something up on purpose still works.
        HideRule(
            name = "discoverTab",
            selector = "a[data-e2e='discover-icon']",
        ),

        // The centre "+" in the bottom bar. On mobile web it isn't an upload button: it opens the
        // native app (or the App Store), which throws you out of InnerCircle entirely.
        HideRule(
            name = "openAppButton",
            selector = "[data-e2e='open-titok-icon']",
        ),

        // The app-install interstitial that covers the video after a couple of swipes
        // ("Get the full app experience"). Verified: it appears as [data-e2e='middle-cta-container']
        // with confirm/cancel buttons under it. Hiding the container leaves the feed underneath usable.
        HideRule(
            name = "appInstallInterstitial",
            selector = "[data-e2e='middle-cta-container'], [data-e2e='modal-close-inner-button']",
        ),
    )

    /**
     * Text rules, for the bits with no stable hook. Exact match on leaf elements, trimmed and
     * case-insensitive, so a caption containing the words isn't hidden. English UI only.
     */
    override val textRules: List<TextRule> = listOf(
        // The header "Open app" button. It has no data-e2e of its own (verified: its only stable
        // feature is being a <button> whose entire text is "Open app").
        TextRule(
            name = "openAppHeaderButton",
            textSelector = "button",
            texts = listOf("Open app", "Open TikTok"),
            hideClosest = "button",
        ),
    )

    /**
     * Ads and suggested videos inside the Following feed's pager. Covered in place rather than
     * hidden — see [SlideRule] for the measurement that rules out collapsing.
     *
     * The slide selector is verified: every video in the pager is a
     * `[data-e2e='video-slide-0' | 'video-slide-1' | ... | 'video-slide-active']` container.
     * The markers are NOT verified against a live ad (no ad could be loaded logged out).
     */
    override val slideRules: List<SlideRule> = listOf(
        // The For You feed itself, for when [routeRedirects] can't get off it. Measured on the live
        // site: logged out, TikTok answers /following by sending you straight back to /foryou, so
        // after a few rounds the redirect gives up rather than reload forever (see RouteRedirect).
        // Rather than leave the algorithm playing, every slide on that route is covered: no marker
        // selectors, so the rule matches all of them. The page around the pager is untouched, so
        // the login button, the search and the nav still work and logging in gets you a real
        // Following feed.
        SlideRule(
            name = "forYouFeed",
            slideSelector = SLIDE_SELECTOR,
            pathPattern = FOR_YOU_PATH,
            label = "For You feed hidden.\nLog in to TikTok to see the people you follow.",
        ),

        SlideRule(
            name = "sponsoredSlides",
            slideSelector = SLIDE_SELECTOR,
            // Ads link out of tiktok.com, to the advertiser or through TikTok's own ad/business
            // links; organic slides only ever link to /@user, /music/... and /tag/... (verified).
            markerSelectors = listOf(
                "a[href*='ads.tiktok.com']",
                "a[href*='/business']",
                "a[target='_blank'][href^='http']:not([href*='tiktok.com'])",
                "[data-e2e*='ad-']",
                "[data-e2e*='-ad']",
            ),
            markerTexts = listOf("Sponsored", "Ad", "Paid partnership", "Promoted"),
            label = "Ad hidden",
        ),

        // Suggested/not-followed videos. On the Following feed TikTok mixes in "Suggested accounts"
        // strips and, when it runs out of new videos from people you follow, videos from accounts
        // you don't. The strips have a verified hook; the standalone suggested video does not, so
        // this is the part most likely to need re-inspecting.
        SlideRule(
            name = "suggestedSlides",
            slideSelector = SLIDE_SELECTOR,
            markerSelectors = listOf("[data-e2e='suggest-card']", "[data-e2e='suggest-accounts']"),
            markerTexts = listOf("Suggested accounts", "Suggested for you", "You may like"),
            label = "Suggested video hidden",
        ),
    )

    /**
     * Keep the user in the feed they chose. `/` and `/foryou` are the algorithm; `/explore` is the
     * trending grid. Each is replaced with `/following`.
     *
     * Verified on the live site: `/` redirects to `/foryou` by itself, `/explore` does too, and
     * `/following` is a real route. Logged out, though, TikTok bounces `/following` straight back
     * to `/foryou`, so the redirect and the site volley until the engine's per-rule cap stops it
     * (three rounds), and the "forYouFeed" slide rule above covers what's left. Logged in the
     * bounce shouldn't happen, but that could not be tested: no test account was available.
     */
    override val routeRedirects: List<RouteRedirect> = listOf(
        RouteRedirect(name = "forYouToFollowing", fromPattern = FOR_YOU_PATH, to = FOLLOWING_PATH),
        RouteRedirect(name = "exploreToFollowing", fromPattern = "^/(explore|discover)\\b", to = FOLLOWING_PATH),
    )

    /**
     * What the Stats screen counts. A "post" is a video slide that stayed on screen for a moment.
     *
     * TikTok's DOM carries no video id (verified: slides have only `data-e2e` and a generated
     * class, and the <video> src is a blob: URL), so videos are told apart by their cover image,
     * which is per-video. FRAGILE, and approximate by design: if the cover image element changes,
     * the count falls back to counting each slide element once, and the pager reuses those, so the
     * number would read low rather than wrong-high.
     */
    override val tracking: TrackingRule = TrackingRule(
        postSelector = SLIDE_SELECTOR,
        postKeySelector = "img[data-fmp]",
        postKeyAttr = "src",
        minPostHeight = 200,
        postDwellMs = 1000,
        storyPathPattern = "", // TikTok's mobile web has no story viewer
    )

    private const val FOLLOWING_PATH = "/following"
    private const val FOR_YOU_PATH = "^/(foryou)?/?$"
    private const val SLIDE_SELECTOR = "[data-e2e^='video-slide']"
}
