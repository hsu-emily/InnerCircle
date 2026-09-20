# InnerCircle

An Android app that lets you use Instagram on your own terms. It opens Instagram's mobile website inside
the app, removes the parts designed to keep you scrolling (ads, suggested posts, Reels, the endless feed
after "you're all caught up"), measures how you actually use it, and turns those numbers into a short,
personal habit summary with an AI.

It started as a hackathon project. It is a personal tool, not a Play Store product, and it is **not
affiliated with or endorsed by Instagram or Meta** (see [Limits and honest caveats](#limits-and-honest-caveats)).

**Built with:** Kotlin, Jetpack Compose, Android WebView, plus a small Cloudflare Worker for the AI.

---

## What it does

**A calmer Instagram**
- Hides feed ads, "Suggested for you" posts and the Reels tab.
- Sends the Search tab straight to the search box with your recent searches, instead of the Explore grid.
- Locks reels so you only ever see the one you opened. It works in a reel sent in a DM too.
- Skips story ads automatically.
- Treats "You're all caught up" as the end of your feed: no suggested posts after it.

**Statistics you can trust**
- Screen time, time of day, stories viewed, posts seen and scroll distance, for a day, week or month.
- "Today vs Yesterday" (and week / month) comparison, a day-by-day chart, and progress against your own daily limit.

**A personal habit summary**
- On the Day view, an AI writes a short **usage summary** and a few **wellness recommendations** from your
  numbers and your survey answers. It appears on its own when you open Statistics.

**Made for leaving**
- Get back to InnerCircle with a draggable floating button, a swipe from the left edge, or both
  (your choice in Settings). Android's Back gesture always works too.

## The app in five screens

| Screen | What it is |
| --- | --- |
| **Splash** | Logo and name. |
| **Survey** (first run only) | Six questions: your goals, typical daily use, when it's hardest to stop, what pulls you in, your daily limit, and anything else in your own words. This is what makes the advice personal. |
| **Apps** | The platforms. Instagram is live; YouTube, TikTok, Facebook and LinkedIn are shown faded as "coming soon". |
| **Statistics** | Day / Week / Month with a date stepper, the numbers above, and the habit summary. |
| **Settings** | Daily limit, how you leave an app, retake the survey, and how AI insights work. |

---

## How it works

```
┌──────────────────────── InnerCircle app ────────────────────────┐
│  Compose UI  ──►  ViewModels  ──►  Repositories (on-device)     │
│      │                                  ▲                        │
│      ▼                                  │ usage numbers          │
│  WebView ── injected JS ──► UsageTracker┘                        │
│   │   (feed_cleaner.js + usage_tracker.js)                       │
│   ▼                                                              │
│  instagram.com          InsightClient ──► your Worker ──► OpenAI │
└──────────────────────────────────────────────────────────────────┘
```

### 1. Instagram in a WebView, cleaned as it loads
`webview/InstagramWebView.kt` shows `instagram.com` and, after every page load, injects two scripts from
`app/src/main/assets/`. Instagram is a single-page app, so the scripts keep running and react as the feed
changes. **Every selector lives in one file, `webview/InstagramSelectors.kt`**, so when Instagram changes
its page, that is the file to edit. The engine itself (`feed_cleaner.js`) has no Instagram-specific
knowledge.

| Rule | How it works |
| --- | --- |
| Reels tab, "Original audio" links | CSS `display: none`. |
| Ad posts | CSS: any post containing a link to `/ads/` (Instagram's ad redirect), so it doesn't depend on the label text or language. |
| Ad / "Suggested for you" labels | Text rules: leaf elements whose whole text matches, then hide their post. Only newly added parts of the page are scanned, with a full re-scan every few seconds as a safety net. |
| Search tab | A small route state machine: `/explore/` is never shown; arriving there focuses the search box, and a cover hides the transition. |
| Reel paging | Recognised by *shape* (a scroller much taller than its box holding a near-full-height video) because Instagram's class names are obfuscated. That shape is then frozen. |
| Story ads | Detected by a visible `/ads/` link or an "Ad"/"Sponsored" tag near the top. A black frame covers the ad in the same frame it appears, then the app taps "next". |
| End of feed | After "You're all caught up", the posts stay laid out but invisible and the page can't scroll past the marker. |

Two design decisions worth knowing about, both learned the hard way:
- **Feed posts are collapsed to zero height, not `display: none`.** Instagram's feed is virtualized and
  stops loading if items vanish entirely; the page would go white after some scrolling.
- **The end-of-feed rule does not collapse suggested posts.** Doing so made Instagram request more posts in
  an endless loop (18 requests in 9 seconds in a test). Keeping them laid out but invisible, and stopping
  the scroll, is what makes it safe.

The story-ad "tap next" is a real touch injected by the app (`webview/NativeTap.kt`), not a scripted
click, because a scripted click is an "untrusted" event a page can ignore. The page asks for it over an
origin-restricted message channel, and the app only honours it inside stories, on the right-hand middle of
the screen, and no faster than one every 0.3 seconds.

### 2. Measuring your use
`tracking/UsageTracker.kt` and `assets/usage_tracker.js`:

- **Time:** counted while an app's screen is in the foreground inside InnerCircle, using the phone's
  monotonic clock (so changing the phone's time can't add or remove minutes) and saved every 5 seconds.
  Wall-clock time only decides which day and hour it belongs to. Time in Instagram *outside* InnerCircle
  is not counted.
- **Posts seen:** a post counts once when it is mostly on screen for at least a second. Hidden ads and
  suggestions never count.
- **Stories:** each story opened, taken from the route. Skipped story ads are excluded.
- **Scroll distance:** page movement converted to metres from the screen's pixel density. It's an
  estimate, typically within about 10%.

Everything the page reports is clamped natively (it is third-party code, so it isn't trusted), and all
numbers are stored **on the device only**.

### 3. Statistics
`data/PeriodMath.kt` turns the stored days into a day / week / month summary, including the previous
period for comparison. `ui/stats/` renders it. Pure functions such as `summarizeUsage`, `changeBetween`
and `usageWindows` are unit-tested.

### 4. The AI summary
The app never contains an OpenAI key. Instead:

1. `ai/InsightPrompt.kt` writes up your survey answers and aggregate numbers (totals, per-app time, busiest
   hours, days over your limit). **No account names, no post content.**
2. `ai/InsightClient.kt` sends that text to a small Cloudflare Worker (`server/insights-worker/`) with an
   anonymous random install id.
3. The Worker holds the real key and the fixed instructions, calls OpenAI (`gpt-4o-mini`), and returns
   plain text with a **Usage Summary** and **Wellness Recommendations** section.
4. The app parses that into the card.

The Worker also enforces limits: 10 summaries per install per day, 40 per IP and 1,500 overall, and it
logs nothing from requests. See [`server/insights-worker/README.md`](server/insights-worker/README.md).

**When it writes one** (`ui/stats/InsightPolicy.kt`): only on the Day view, only when that day has data,
and only when the saved one is missing or stale. A finished day is written once and kept. Today is
refreshed after at least 15 more minutes of use or after you edit your survey answers. Flicking through
days waits a moment first so it doesn't send a request for each one.

---

## Project layout

```
app/src/main/
├── assets/
│   ├── feed_cleaner.js        the page-cleaning engine (generic)
│   └── usage_tracker.js       counts posts, stories and scrolling in the page
└── java/com/emilyhsu/innercircle/
    ├── webview/               WebView, injected scripts' config, native tap
    │   └── InstagramSelectors.kt   ← every Instagram-specific selector lives here
    ├── tracking/              UsageTracker (time + page reports)
    ├── data/                  models, repositories, day/week/month maths
    ├── ai/                    prompt, client, endpoint config
    ├── ui/                    Compose screens: splash, survey, apps, stats, settings, web
    └── util/                  formatting helpers
server/insights-worker/        the Cloudflare Worker + local test server
```

Plain manual dependency injection (`InnerCircleApp` holds the repositories), `StateFlow` view models, and
`SharedPreferences` for storage. There is no backend for user data.

---

## Getting started

**You need:** Android Studio (its bundled JDK works), an emulator or phone on Android 8.0+ (API 26+), and
Node 18+ only if you want the AI locally.

```bash
# build and run tests
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
```

Then open the project in Android Studio and press **Run** (▶). If `./gradlew` says it can't find Java, point
`JAVA_HOME` at Android Studio's bundled JDK (`Android Studio.app/Contents/jbr/Contents/Home` on a Mac).

### AI summaries on the emulator
Debug builds already point at a server on your computer. Put your OpenAI key in
`server/insights-worker/.dev.vars` (this file is git-ignored and is never sent anywhere):

```
OPENAI_API_KEY=sk-...
```

```bash
cd server/insights-worker
npm run dev:real     # keep this terminal open while you use the app
```

Without it, Statistics says it couldn't reach the insights service, and everything else still works.
The emulator reaches your computer at `10.0.2.2`; a physical phone can't, so it needs a deployed Worker.

### Deploying the Worker (for a phone, or other people)
Follow [`server/insights-worker/README.md`](server/insights-worker/README.md) (about 10 minutes, free
Cloudflare account), then paste the URL into `DEPLOYED_ENDPOINT` in `ai/AiConfig.kt`.

### Tests
```bash
./gradlew :app:testDebugUnitTest     # app: usage maths, parsing, prompt, tracker, labels, ...
cd server/insights-worker && npm test # Worker: limits, error handling, no key leakage
```

---

## Security and privacy

- **No secrets in the app or the repo.** The OpenAI key exists only as a Worker secret (and, for local
  testing, in the git-ignored `.dev.vars`).
- **Your data stays on your device.** The only thing that leaves is the anonymous summary text above.
- `allowBackup` is off, because the app stores a logged-in Instagram session and your usage history.
- The page-to-app message channel accepts messages only from `https://www.instagram.com`.
- Plain-HTTP traffic is allowed only in debug builds, and only to the emulator's `10.0.2.2` address.

## Limits and honest caveats

- **Terms of service.** Modifying how Instagram's website behaves may go against Instagram's Terms of Use.
  This is a personal project; use it at your own risk and don't distribute it as if it were official.
- **Instagram can break it at any time.** The selectors and the English wording ("Ad", "Suggested for you",
  "You're all caught up") are the fragile parts. Everything is in `InstagramSelectors.kt`.
- **Story-ad detection is best-effort.** It is tested against a simulated story viewer and Instagram's known
  ad markers, not against a large sample of real ads. Debug builds write what it detected to Logcat (tag
  `InnerCircleJS`), which makes a miss easy to diagnose.
- **Only Instagram works today.** The other platforms are placeholders.
- **English only** for the text-based rules.
- **Not hardened for a public release.** Anyone can invent an install id, so the Worker's per-IP and global
  limits are the real ceiling. A public release should add Play Integrity or Firebase App Check.
- **Emulators are slow at Instagram.** The page is heavy; a real phone will feel noticeably snappier.

## Credits

App logos are drawn from the [Simple Icons](https://simpleicons.org) project (CC0). The brand logos are
trademarks of their owners.
