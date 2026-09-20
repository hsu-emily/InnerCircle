/*
 * InnerCircle usage tracker.
 *
 * Runs inside the page next to feed_cleaner.js and reports what the user actually did, as small
 * deltas, to the app through `window.InnerCircleBridge` (an origin-restricted WebMessageListener
 * registered in PlatformWebView). Time on app is measured natively; this covers the parts only
 * the page knows:
 *
 *   posts    - feed posts that were mostly on screen for a moment (hidden ads/suggested posts are
 *              zero-height and never count)
 *   stories  - distinct stories opened, taken from the route (/stories/<user>/<id>/)
 *   scrollPx - vertical distance the feed was scrolled, in CSS px
 *
 * Config (see the platform's selector file): {postSelector, postKeySelector, minPostHeight, postDwellMs,
 * storyPathPattern}. Like feed_cleaner.js it can be pasted into the chrome://inspect console.
 */
(function () {
  'use strict';

  var TAG = '[InnerCircle]';
  var FLUSH_MS = 4000;
  var PATH_POLL_MS = 200;

  if (window.__icTracker) {
    try { window.__icTracker.dispose(); } catch (e) { console.error(TAG + ' tracker dispose failed :: ' + e); }
  }

  var cfg = null;
  var io = null;
  var mo = null;
  var flushTimer = null;
  var pathTimer = null;
  var scanQueued = false;

  var counts = { posts: 0, stories: 0, scrollPx: 0 };
  var observed = new WeakSet();   // article elements handed to the IntersectionObserver
  var dwell = new WeakMap();      // element -> pending "still visible?" timeout
  var seenPostKeys = {};          // permalink -> true (Instagram re-mounts posts as you scroll back)
  var seenPosts = new WeakSet();  // fallback identity for posts without a permalink
  var seenStories = {};           // story id -> true
  var pendingStories = [];        // opened, not yet counted: { path, at }
  var lastY = null;
  var lastPath = null;

  function send() {
    if (!counts.posts && !counts.stories && counts.scrollPx < 1) return;
    if (!window.InnerCircleBridge) return; // keep counting; the bridge may appear on the next page load
    var msg = JSON.stringify({
      type: 'usage',
      posts: counts.posts,
      stories: counts.stories,
      scrollPx: Math.round(counts.scrollPx),
    });
    try {
      window.InnerCircleBridge.postMessage(msg);
      counts = { posts: 0, stories: 0, scrollPx: 0 };
    } catch (e) {
      console.error(TAG + ' tracker send failed :: ' + (e.stack || e));
    }
  }

  // --- posts ---------------------------------------------------------------------------------------
  function postKey(el) {
    var a = cfg.postKeySelector ? el.querySelector(cfg.postKeySelector) : null;
    return a ? a.getAttribute('href') : null;
  }

  function stillInView(el) {
    var r = el.getBoundingClientRect();
    return r.height >= cfg.minPostHeight && r.top < innerHeight * 0.7 && r.bottom > innerHeight * 0.3;
  }

  function countPost(el) {
    var key = postKey(el);
    if (key) {
      if (seenPostKeys[key]) return;
      seenPostKeys[key] = true;
    } else {
      if (seenPosts.has(el)) return;
      seenPosts.add(el);
    }
    counts.posts++;
  }

  function onIntersect(entries) {
    entries.forEach(function (e) {
      var el = e.target;
      var inView = e.isIntersecting && (e.intersectionRatio >= 0.5 || e.intersectionRect.height >= innerHeight * 0.5);
      if (inView && el.getBoundingClientRect().height >= cfg.minPostHeight) {
        if (dwell.has(el)) return;
        dwell.set(el, setTimeout(function () {
          dwell.delete(el);
          if (stillInView(el)) countPost(el);
        }, cfg.postDwellMs));
      } else if (dwell.has(el)) {
        clearTimeout(dwell.get(el));
        dwell.delete(el);
      }
    });
  }

  function observeNewPosts() {
    scanQueued = false;
    var posts = document.querySelectorAll(cfg.postSelector);
    for (var i = 0; i < posts.length; i++) {
      if (!observed.has(posts[i])) {
        observed.add(posts[i]);
        io.observe(posts[i]);
      }
    }
  }

  function queueScan() {
    if (scanQueued) return;
    scanQueued = true;
    setTimeout(observeNewPosts, 300);
  }

  // --- scroll distance -------------------------------------------------------------------------------
  function onScroll() {
    var y = (document.scrollingElement || document.documentElement).scrollTop;
    if (lastY !== null) {
      var d = Math.abs(y - lastY);
      // Big jumps are the page resetting its scroll on navigation, not the user's thumb.
      if (d > 0 && d < innerHeight * 2.5) counts.scrollPx += d;
    }
    lastY = y;
  }

  // --- stories ---------------------------------------------------------------------------------------
  // A story is counted STORY_SETTLE_MS after it opens, not the instant the route changes. That gives
  // feed_cleaner.js time to recognise a story ad and mark its path, so ads you were skipped past
  // aren't counted. Deciding by path (not by what's showing now) keeps quick tap-throughs counted.
  var STORY_SETTLE_MS = 500;

  function isStoryAd(path) {
    var engine = window.__innerCircle;
    return !!(engine && engine.isStoryAd && engine.isStoryAd(path));
  }

  function settleStories() {
    var now = Date.now();
    pendingStories = pendingStories.filter(function (s) {
      if (now - s.at < STORY_SETTLE_MS) return true;
      if (!isStoryAd(s.path)) counts.stories++;
      return false;
    });
  }

  function checkPath() {
    var p = location.pathname;
    if (p !== lastPath) { lastPath = p; lastY = null; }
    if (!cfg.storyPathPattern) return;
    settleStories();
    var m = new RegExp(cfg.storyPathPattern).exec(p);
    if (m && m[1] && !seenStories[m[1]]) {
      seenStories[m[1]] = true;
      pendingStories.push({ path: p, at: Date.now() });
    }
  }

  function onVisibility() { if (document.visibilityState === 'hidden') send(); }

  function install(config) {
    cfg = config;
    io = new IntersectionObserver(onIntersect, { threshold: [0, 0.25, 0.5, 0.75, 1] });
    mo = new MutationObserver(queueScan);
    mo.observe(document.documentElement, { childList: true, subtree: true });
    window.addEventListener('scroll', onScroll, { passive: true });
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', send);
    flushTimer = setInterval(send, FLUSH_MS);
    pathTimer = setInterval(checkPath, PATH_POLL_MS);
    observeNewPosts();
    checkPath();
    // Start from where the page already is, so the very first movement is counted too.
    lastY = (document.scrollingElement || document.documentElement).scrollTop;
    console.log(TAG + ' usage tracker installed');
  }

  function dispose() {
    send(); // don't lose what hasn't been reported yet
    if (io) io.disconnect();
    if (mo) mo.disconnect();
    io = mo = null;
    window.removeEventListener('scroll', onScroll);
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('pagehide', send);
    clearInterval(flushTimer);
    clearInterval(pathTimer);
  }

  window.__icTracker = { install: install, dispose: dispose };
})();
