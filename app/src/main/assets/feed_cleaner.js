/*
 * InnerCircle feed cleaner engine.
 *
 * Generic on purpose: it knows nothing about Instagram's DOM. What to hide, which routes to treat
 * specially and which selectors to use all come from the config that FeedCleaner.kt builds out of
 * InstagramSelectors.kt and passes to install().
 *
 * Because it's plain JS you can also paste this whole file into the chrome://inspect console, then
 * call  __innerCircle.install({...})  to try things live. Re-running install()/pasting again
 * replaces the previous instance.
 *
 * Config shape (see InstagramSelectors.kt for the source of truth):
 *   hideRules:   [{name, selector, pathPattern?, keepLayoutBox}]   optionally route-scoped
 *   textRules:   [{name, textSelector, texts, hideClosest, keepLayoutBox}]
 *   explore:     {explorePath, searchPath, searchInputSelector, tabSelector}
 *   scrollLocks: [{name, pathPattern?, videoSelector, minScrollRatio, minVideoHeightRatio}]
 *   routeBlocks: [{name, pathPattern, destination, mediaSelector}]
 *
 * keepLayoutBox: hide by collapsing to zero height instead of display:none. Instagram virtualizes
 * the feed and relies on every post keeping a box (its observers never fire for display:none
 * elements), so hiding posts with display:none makes the feed stop loading and go blank.
 */
(function () {
  'use strict';

  var TAG = '[InnerCircle]';           // FeedCleaner.LOG_PREFIX on the Kotlin side
  var STYLE_ID = '__innercircle_hide_style';
  var RULE_ATTR = 'data-ic-rule';      // marks elements hidden by a text rule
  var LOCK_GRACE_MS = 1500;            // after locking, tolerate Instagram positioning the pager itself
  var LOCK_SCAN_MS = 250;              // how often to look for a pager while none is locked
  var COVER_ID = '__innercircle_cover';
  var COVER_MAX_MS = 5000;             // never leave the cover up longer than this, whatever happens
  var SPIN = '__ic_spin';
  var AD_SKIP_STEP_MS = 400;           // wait between attempts to move past a story ad
  var AD_COVER_ID = '__innercircle_adcover';
  var AD_COVER_MAX_MS = 2500;          // the ad cover always comes down by itself, whatever happens
  var AD_POLL_MS = 250;                // story ads are looked for on a timer too, see install()
  var TAP_X = 0.85, TAP_Y = 0.5;       // where the viewer's "next" area is, as fractions of the screen
  var AD_SKIP_MAX_TRIES = 4;           // then give up and leave the ad showing rather than trap the user

  // Zero-height, but still a real box. See "keepLayoutBox" above.
  var COLLAPSE = [
    ['height', '0'], ['min-height', '0'], ['max-height', '0'], ['overflow', 'hidden'],
    ['margin', '0'], ['padding', '0'], ['border', '0'], ['pointer-events', 'none'],
  ];
  var COLLAPSE_CSS = COLLAPSE.map(function (p) { return p[0] + ': ' + p[1] + ' !important;'; }).join(' ');
  var REMOVE_CSS = 'display: none !important;';

  function log(msg) { console.log(TAG + ' ' + msg); }
  function fail(msg, e) { console.error(TAG + ' ' + msg + (e ? ' :: ' + (e.stack || e) : '')); }
  function matches(pattern, s) { return new RegExp(pattern).test(s || ''); }

  // Each onPageFinished re-injects; tear down any previous instance first.
  if (window.__innerCircle) {
    try { window.__innerCircle.dispose(); } catch (e) { fail('dispose of previous instance failed', e); }
  }

  var config = null;
  var observer = null;
  var scheduled = false;
  var lastCounts = {};   // rule name -> last logged match count (only log on change)
  var failedRules = {};  // rule name -> true once its error was logged (avoid per-frame spam)

  // A route block is used for a page whose entire interaction model is endless paging (currently
  // YouTube Shorts). Remembering the path makes a slow navigation idempotent while mutations land.
  var blockingPath = null;

  // Route state for the explore -> search behavior.
  var lastPath = null;
  var leavingSearch = false;   // true while we're backing out of explore/search to wherever the user came from
  var wantSearchOpen = false;  // true while we're waiting for the search input to appear so we can focus it
  var searchAttempts = 0;

  var coverTimer = null;
  var locks = [];        // active scroll locks

  // What changed since the last pass. Rescanning the whole feed on every change made Instagram sluggish,
  // so text rules only look at newly added nodes, with a full scan now and then as a safety net.
  var pendingNodes = [];
  var fullScan = true;
  var lastFullScan = 0;
  var lastCountAt = 0;

  // End of feed: the "You're all caught up" marker, and how far the page may scroll because of it.
  var AFTER_ATTR = 'data-ic-after';   // posts after the marker, made invisible but left in place
  var END_PAD = 16;                   // breathing room under the marker, in px
  var caughtUp = null;
  var endLimit = Infinity;
  var FULL_SCAN_MS = 5000;     // safety net for text changed in place, which the observer doesn't report
  var COUNT_LOG_MS = 2000;     // how often the "N matches" diagnostics are recomputed
  var MAX_PENDING = 400;       // past this, a full scan is cheaper than walking every added node

  // Story ads: paths recognised as ads (the usage tracker asks, so ads aren't counted as stories),
  // and the state of the skip in progress.
  var adPaths = {};
  var adSkip = { path: null, tries: 0, timer: null };
  var adPollTimer = null;
  var adCoverTimer = null;
  var storyObserver = null;
  var storyCheckQueued = false;
  var prevAd = { el: null, path: null };   // the ad we last skipped: its markup can linger while the next story loads
  var snapPath = null;                     // debug: the story path last recorded

  // One bad selector must not take down the other rules, so every rule runs inside guard().
  function guard(ruleName, fn) {
    try {
      fn();
    } catch (e) {
      if (!failedRules[ruleName]) {
        failedRules[ruleName] = true;
        fail('rule "' + ruleName + '" threw (invalid selector?)', e);
      }
    }
  }

  function reportCount(ruleName, count) {
    if (lastCounts[ruleName] === count) return;
    lastCounts[ruleName] = count;
    log(ruleName + ': ' + count + ' matches' + (count === 0 ? ' (selector stale? re-inspect the DOM)' : ''));
  }

  // --- Route blocks: escape endless full-screen players -----------------------------------------
  // Pausing is deliberately first: a player can be mounted before navigation settles. `replace`
  // means Android Back won't return to the Short and restart its pager.
  function applyRouteBlocks() {
    var blocks = config.routeBlocks || [];
    var path = location.pathname;
    for (var i = 0; i < blocks.length; i++) {
      var rule = blocks[i];
      if (!matches(rule.pathPattern, path)) continue;
      var media = document.querySelectorAll(rule.mediaSelector || 'video');
      for (var j = 0; j < media.length; j++) {
        media[j].autoplay = false;
        media[j].removeAttribute('autoplay');
        try { media[j].pause(); } catch (e) { /* a not-yet-ready media element is harmless */ }
      }
      if (blockingPath !== path) {
        blockingPath = path;
        log(rule.name + ': paused ' + media.length + ' media element(s); leaving blocked route');
        location.replace(rule.destination);
      }
      return true;
    }
    blockingPath = null;
    return false;
  }

  // --- Hide rules: pure CSS -------------------------------------------------------------------
  function activeHideRules() {
    var path = location.pathname;
    return config.hideRules.filter(function (r) { return !r.pathPattern || matches(r.pathPattern, path); });
  }

  // One CSS rule per selector: an invalid selector in a comma list would otherwise void the whole rule.
  // Rebuilt when the set of rules active for the current route changes (route-scoped rules).
  function ensureStyle(active) {
    var sig = active.map(function (r) { return r.name + (r.keepLayoutBox ? '*' : ''); }).join(',');
    var style = document.getElementById(STYLE_ID);
    if (style && style.getAttribute('data-sig') === sig) return;
    if (!style) {
      style = document.createElement('style');
      style.id = STYLE_ID;
      (document.head || document.documentElement).appendChild(style);
    }
    style.setAttribute('data-sig', sig);
    style.textContent = active.map(function (r) {
      return r.selector + ' { ' + (r.keepLayoutBox ? COLLAPSE_CSS : REMOVE_CSS) + ' }';
    }).join('\n') + '\n@keyframes ' + SPIN + ' { to { transform: rotate(360deg); } }';
  }

  // Inline-style counterpart of the CSS above, for elements found by text rules.
  function hideElement(el, keepLayoutBox) {
    if (keepLayoutBox) {
      COLLAPSE.forEach(function (p) { el.style.setProperty(p[0], p[1], 'important'); });
    } else {
      el.style.setProperty('display', 'none', 'important');
    }
  }

  function unhideElement(el) {
    el.style.removeProperty('display');
    COLLAPSE.forEach(function (p) { el.style.removeProperty(p[0]); });
  }

  // --- Text rules: find label text, hide the containing element ---------------------------------
  function textCandidates(rule, nodes) {
    if (!nodes) return document.querySelectorAll(rule.textSelector);
    var found = [];
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (!n.isConnected) continue;
      if (n.matches(rule.textSelector)) found.push(n);
      var inside = n.querySelectorAll(rule.textSelector);
      for (var j = 0; j < inside.length; j++) found.push(inside[j]);
    }
    return found;
  }

  // [nodes] is the list of elements added since the last pass, or null to scan the whole page.
  function applyTextRule(rule, nodes) {
    var wanted = rule.texts.map(function (t) { return t.trim().toLowerCase(); });
    var candidates = textCandidates(rule, nodes);
    var hiddenNow = 0;
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (el.childElementCount !== 0) continue; // leaf elements only: cheap, and avoids matching wrappers
      var text = (el.textContent || '').trim().toLowerCase();
      if (wanted.indexOf(text) === -1) continue;
      if (rule.endOfFeed) {
        if (el !== caughtUp) { caughtUp = el; log('endOfFeed: found the "all caught up" marker'); }
        continue;
      }
      var target = el.closest(rule.hideClosest);
      if (target && !target.hasAttribute(RULE_ATTR)) {
        target.setAttribute(RULE_ATTR, rule.name);
        hideElement(target, rule.keepLayoutBox);
        hiddenNow++;
      }
    }
    if (!rule.endOfFeed && (!nodes || hiddenNow)) reportCount(rule.name, document.querySelectorAll('[' + RULE_ATTR + '="' + rule.name + '"]').length);
  }

  function goBackOnce() {
    var before = location.pathname;
    history.back();
    setTimeout(function () {
      // Nothing earlier in history (e.g. the app started on search): fall back to home.
      if (leavingSearch && location.pathname === before) {
        leavingSearch = false;
        location.assign('/');
      }
    }, 700);
  }

  function onRouteChange(prev, path) {
    var ex = config.explore;
    if (!ex) return;
    var onExplore = matches(ex.explorePath, path);
    var onSearch = matches(ex.searchPath, path);

    if (leavingSearch) {
      if (onExplore || onSearch) goBackOnce(); else leavingSearch = false;
      return;
    }
    wantSearchOpen = false;
    if (onExplore) {
      if (prev && matches(ex.searchPath, prev)) {
        leavingSearch = true;
        showCover();
        goBackOnce();
      } else {
        wantSearchOpen = true;
        searchAttempts = 0;
        showCover(); // e.g. cold start on /explore/; the tab tap already raised it in the normal case
      }
    }
  }

  // --- Cover: hide Instagram's route transitions ------------------------------------------------
  // After a tap on the Search tab Instagram keeps painting the old page for up to a couple of
  // seconds while it loads the Explore route, then shows an empty shell, and only then can we
  // move on to the search view. A cover the colour of the page (with a small spinner) goes up on
  // the tap and comes down once the search view is showing, so none of that intermediate state is
  // seen. The bottom tab bar is left uncovered so navigation still feels live.
  function navBottomInset() {
    var tab = config.explore && document.querySelector(config.explore.tabSelector);
    for (var n = tab; n && n !== document.body; n = n.parentElement) {
      var pos = getComputedStyle(n).position;
      if (pos === 'fixed' || pos === 'sticky') {
        return Math.max(0, Math.round(innerHeight - n.getBoundingClientRect().top));
      }
    }
    return 60; // tab bar not found: a typical bar height
  }

  function pageBackground() {
    var c = getComputedStyle(document.body).backgroundColor;
    if (!c || c === 'transparent' || /rgba\(.*,\s*0\)$/.test(c)) c = getComputedStyle(document.documentElement).backgroundColor;
    return (!c || c === 'transparent' || /rgba\(.*,\s*0\)$/.test(c)) ? '#fff' : c;
  }

  function showCover() {
    if (document.getElementById(COVER_ID)) return;
    var cover = document.createElement('div');
    cover.id = COVER_ID;
    cover.style.cssText = 'position:fixed;left:0;right:0;top:0;bottom:' + navBottomInset() + 'px;' +
      'z-index:2147483646;display:flex;align-items:center;justify-content:center;background:' + pageBackground() + ';';
    var spinner = document.createElement('div');
    spinner.style.cssText = 'width:22px;height:22px;border-radius:50%;border:2px solid rgba(128,128,128,.35);' +
      'border-top-color:rgba(128,128,128,.9);animation:' + SPIN + ' .8s linear infinite;';
    cover.appendChild(spinner);
    (document.body || document.documentElement).appendChild(cover);
    coverTimer = setTimeout(hideCover, COVER_MAX_MS); // safety net
  }

  function hideCover() {
    if (coverTimer) clearTimeout(coverTimer);
    coverTimer = null;
    var cover = document.getElementById(COVER_ID);
    if (cover) cover.remove();
  }

  // The cover comes down once the search view is up, or as soon as we're on neither route. While we
  // are walking back out of search it stays up until we've left.
  function updateCover() {
    var ex = config.explore;
    if (!ex || leavingSearch || !document.getElementById(COVER_ID)) return;
    var path = location.pathname;
    var onSearch = matches(ex.searchPath, path);
    var onExplore = matches(ex.explorePath, path);
    if ((onSearch && document.querySelector(ex.searchInputSelector)) || (!onSearch && !onExplore)) hideCover();
  }

  // Raise the cover on the tap itself, before Instagram starts its transition. And if you're already
  // in the search view, tapping the Search tab should just stay there.
  function onTabClick(e) {
    var ex = config.explore;
    var tab = ex && e.target && e.target.closest && e.target.closest(ex.tabSelector);
    if (!tab) return;
    if (matches(ex.searchPath, location.pathname)) {
      e.preventDefault();
      e.stopImmediatePropagation();
      return;
    }
    if (!matches(ex.explorePath, location.pathname)) showCover();
  }

  function tryOpenSearch() {
    if (!wantSearchOpen || !config.explore) return;
    if (!matches(config.explore.explorePath, location.pathname)) { wantSearchOpen = false; return; }
    if (++searchAttempts > 60) { wantSearchOpen = false; fail('search input never appeared'); return; }
    var input = document.querySelector(config.explore.searchInputSelector);
    if (!input) return; // not rendered yet; the next mutation pass retries
    input.focus();
    wantSearchOpen = false;
    log('explore: focused search input');
  }

  // --- Scroll lock: stop reel pagers from paging ------------------------------------------------
  // Reels are shown in several places (the /reels/ route, and a full-screen overlay when you open
  // one from a DM that leaves the URL unchanged), all with obfuscated class names. So instead of
  // matching a route or a selector, recognise the *shape* of a pager: a scroll container much taller
  // than its box that holds a near-full-height video. Start from the videos and walk up to the
  // nearest scrolling ancestor, which also keeps this cheap.
  var MAX_ANCESTOR_WALK = 14;

  function findScroller(rule) {
    var videos = document.querySelectorAll(rule.videoSelector);
    for (var i = 0; i < videos.length; i++) {
      var vh = videos[i].getBoundingClientRect().height;
      if (vh < innerHeight * 0.6) continue; // feed/DM videos are far shorter than a full-screen reel
      var p = videos[i].parentElement;
      for (var d = 0; p && p !== document.body && p !== document.documentElement && d < MAX_ANCESTOR_WALK; d++, p = p.parentElement) {
        var oy = getComputedStyle(p).overflowY;
        if ((oy === 'auto' || oy === 'scroll') && p.clientHeight > 0) {
          // Nearest scroller of this video: it's a pager only if it's much taller than its box
          // and the video fills most of it. Either way, don't keep climbing to outer scrollers.
          if (p.scrollHeight > p.clientHeight * rule.minScrollRatio && vh >= p.clientHeight * rule.minVideoHeightRatio) return p;
          break;
        }
      }
    }
    return null;
  }

  // Let things like the comments sheet scroll: only the pager itself is frozen.
  function insideNestedScroller(target, root) {
    for (var n = target; n && n !== root; n = n.parentElement) {
      var oy = getComputedStyle(n).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && n.scrollHeight > n.clientHeight + 1) return true;
    }
    return false;
  }

  function lockScroller(rule, el) {
    var lock = {
      rule: rule,
      el: el,
      top: el.scrollTop,
      since: Date.now(),
      prevValue: el.style.getPropertyValue('overflow-y'),
      prevPriority: el.style.getPropertyPriority('overflow-y'),
    };
    el.style.setProperty('overflow-y', 'hidden', 'important');

    // The clicked reel is whatever the pager is showing once it settles; after that, snap back
    // to it if anything moves the scroller.
    lock.onScroll = function () {
      if (Date.now() - lock.since < LOCK_GRACE_MS) lock.top = el.scrollTop;
      else if (el.scrollTop !== lock.top) el.scrollTop = lock.top;
    };
    el.addEventListener('scroll', lock.onScroll);

    // Instagram sets touch-action: none on the pager and drives swipes itself, so overflow:hidden
    // alone isn't enough: swallow the gestures before its handlers see them.
    lock.block = function (e) {
      if (!el.contains(e.target) || insideNestedScroller(e.target, el)) return;
      if (e.cancelable) e.preventDefault();
      e.stopImmediatePropagation();
    };
    lock.blockKey = function (e) {
      if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown'].indexOf(e.key) === -1) return;
      var t = e.target && e.target.tagName;
      if (t === 'INPUT' || t === 'TEXTAREA' || (e.target && e.target.isContentEditable)) return;
      e.preventDefault();
      e.stopImmediatePropagation();
    };
    ['touchmove', 'wheel', 'pointermove'].forEach(function (type) {
      document.addEventListener(type, lock.block, { capture: true, passive: false });
    });
    document.addEventListener('keydown', lock.blockKey, true);
    return lock;
  }

  function releaseLock(lock) {
    lock.el.removeEventListener('scroll', lock.onScroll);
    ['touchmove', 'wheel', 'pointermove'].forEach(function (type) {
      document.removeEventListener(type, lock.block, { capture: true });
    });
    document.removeEventListener('keydown', lock.blockKey, true);
    if (lock.prevValue) lock.el.style.setProperty('overflow-y', lock.prevValue, lock.prevPriority);
    else lock.el.style.removeProperty('overflow-y');
  }

  function ruleAppliesHere(rule, path) { return !rule.pathPattern || matches(rule.pathPattern, path); }

  var lastLockScan = 0;
  var lockRetryTimer = null;

  function applyScrollLocks() {
    var path = location.pathname;
    // Drop locks whose pager has gone away (overlay closed, navigated elsewhere).
    locks = locks.filter(function (l) {
      var stillNeeded = ruleAppliesHere(l.rule, path) && document.contains(l.el);
      if (!stillNeeded) { releaseLock(l); log(l.rule.name + ': unlocked'); }
      return stillNeeded;
    });
    var now = Date.now();
    if (now - lastLockScan < LOCK_SCAN_MS) {
      // Skipped: make sure we come back even if no further DOM change triggers a pass.
      if (!lockRetryTimer) lockRetryTimer = setTimeout(function () { lockRetryTimer = null; schedule(); }, LOCK_SCAN_MS);
      return;
    }
    lastLockScan = now;
    config.scrollLocks.forEach(function (rule) {
      if (!ruleAppliesHere(rule, path)) return;
      var el = findScroller(rule);
      if (!el || locks.some(function (l) { return l.el === el; })) return;
      locks.push(lockScroller(rule, el));
      log(rule.name + ': locked pager (scrollH=' + el.scrollHeight + ', clientH=' + el.clientHeight + ')');
    });
  }

  // --- Debug recorder ---------------------------------------------------------------------------
  // Debug builds only (config.debug). Writes one line per story to Logcat describing what's on screen
  // (element kinds, aria-labels, rects), so a story ad can be compared with an ordinary story without
  // opening DevTools. Account names are replaced with <user>; text is cut short.
  function short(str, n) {
    str = (str || '').replace(/\s+/g, ' ').trim();
    return str.length > n ? str.slice(0, n) + '\u2026' : str;
  }
  function mask(str) {
    var m = location.pathname.match(/^\/stories\/([^/]+)/);
    return m ? String(str).split(m[1]).join('<user>') : String(str);
  }
  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)];
  }
  function describe(el) {
    var d = { t: el.tagName.toLowerCase() };
    var role = el.getAttribute('role'); if (role) d.role = role;
    var label = el.getAttribute('aria-label'); if (label) d.al = short(mask(label), 30);
    var href = el.getAttribute('href'); if (href) d.href = short(mask(href.split('?')[0]), 50);
    if (!el.children.length) { var text = short(mask(el.textContent), 30); if (text) d.tx = text; }
    d.r = rectOf(el);
    return d;
  }
  function snapshot(reason, matchEl) {
    try {
      var vw = window.innerWidth, vh = window.innerHeight, i, a;
      var out = { reason: reason, path: mask(location.pathname), vp: [vw, vh] };
      if (matchEl) {
        out.match = describe(matchEl);
        out.up = [];
        for (a = matchEl.parentElement, i = 0; a && i < 5; a = a.parentElement, i++) {
          var up = { t: a.tagName.toLowerCase() };
          if (a.getAttribute('role')) up.role = a.getAttribute('role');
          if (a.getAttribute('aria-label')) up.al = short(mask(a.getAttribute('aria-label')), 24);
          out.up.push(up);
        }
      }
      // Everything interactive or textual in the top third: where the name, the "Ad" tag and the close button live.
      out.top = [];
      var all = document.querySelectorAll('body *');
      for (i = 0; i < all.length && out.top.length < 22; i++) {
        var e = all[i], r = e.getBoundingClientRect();
        if (!r.width || !r.height || r.bottom <= 0 || r.top >= vh * 0.33) continue;
        var hasText = !e.children.length && short(e.textContent, 1);
        if (e.matches('a, button, [role=button], [aria-label]') || hasText) out.top.push(describe(e));
      }
      // What a tap on the "next" area lands on.
      out.tapHit = [];
      for (a = document.elementFromPoint(vw * TAP_X, vh * TAP_Y), i = 0; a && i < 4; a = a.parentElement, i++) {
        out.tapHit.push(a.tagName.toLowerCase() + (a.getAttribute('role') ? '[' + a.getAttribute('role') + ']' : '') +
          (a.getAttribute('aria-label') ? '{' + short(mask(a.getAttribute('aria-label')), 20) + '}' : ''));
      }
      var links = document.querySelectorAll("a[href*='/ads/']"), vis = 0, inArticle = 0;
      for (i = 0; i < links.length; i++) { if (links[i].closest('article')) inArticle++; else if (visibleRect(links[i])) vis++; }
      out.adLinks = { all: links.length, visibleOutsideArticle: vis, inArticle: inArticle };
      var text = JSON.stringify(out);
      if (text.length > 3500) { out.top = out.top.slice(0, 10); text = JSON.stringify(out); }
      console.log(TAG + ' [debug] ' + text);
    } catch (err) { fail('debug snapshot failed', err); }
  }

  // --- Story ads ------------------------------------------------------------------------------
  // Ads in the story viewer are separate story items, so there is nothing to hide with CSS. Instead:
  // recognise the ad and move on to the next story.
  function visibleRect(el) {
    var r = el.getBoundingClientRect();
    var onScreen = r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < window.innerHeight &&
      r.right > 0 && r.left < window.innerWidth;
    return onScreen ? r : null;
  }

  // Returns { kind: 'link' | 'label', el } for the signal that fired, or null. Anything inside a feed <article> is
  // ignored: hidden feed ads can still be in the page underneath the viewer.
  function detectStoryAd(rule) {
    var i, j, els;
    for (i = 0; i < rule.adLinkSelectors.length; i++) {
      els = document.querySelectorAll(rule.adLinkSelectors[i]);
      for (j = 0; j < els.length; j++) {
        if (!els[j].closest('article') && visibleRect(els[j])) return { kind: 'link', el: els[j] };
      }
    }
    var wanted = rule.adTexts.map(function (t) { return t.trim().toLowerCase(); });
    var limit = window.innerHeight * rule.headerFraction;
    els = document.querySelectorAll('body *'); // any tag: the label may not be a span
    for (j = 0; j < els.length; j++) {
      var el = els[j];
      if (el.children.length) continue; // leaf elements only, like the text rules
      if (wanted.indexOf((el.textContent || '').trim().toLowerCase()) < 0) continue;
      var r = visibleRect(el);
      if (r && r.bottom <= limit && !el.closest('article')) return { kind: 'label', el: el };
    }
    return null;
  }

  // A plain black frame laid over the story viewer the moment an ad is recognised, so the ad is never
  // seen while it is being skipped. It has no text and lets touches through to the viewer underneath.
  function showAdCover() {
    if (document.getElementById(AD_COVER_ID)) return;
    var cover = document.createElement('div');
    cover.id = AD_COVER_ID;
    cover.style.cssText = 'position:fixed;left:0;right:0;top:0;bottom:0;z-index:2147483646;background:#000;pointer-events:none;';
    (document.body || document.documentElement).appendChild(cover);
    adCoverTimer = setTimeout(hideAdCover, AD_COVER_MAX_MS);
  }

  function hideAdCover() {
    if (adCoverTimer) clearTimeout(adCoverTimer);
    adCoverTimer = null;
    var cover = document.getElementById(AD_COVER_ID);
    if (cover) cover.remove();
  }

  function fireKey(key, code) {
    var opts = { key: key, code: code, keyCode: 39, which: 39, bubbles: true, cancelable: true };
    var target = document.activeElement || document.body;
    target.dispatchEvent(new KeyboardEvent('keydown', opts));
    target.dispatchEvent(new KeyboardEvent('keyup', opts));
  }

  function tapRightSide() {
    var x = window.innerWidth * TAP_X, y = window.innerHeight * TAP_Y;
    var el = document.elementFromPoint(x, y);
    if (!el) return false;
    var opts = { bubbles: true, cancelable: true, clientX: x, clientY: y, pointerType: 'touch', isPrimary: true };
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (type) {
      var Ctor = type.indexOf('pointer') === 0 ? PointerEvent : MouseEvent;
      el.dispatchEvent(new Ctor(type, opts));
    });
    return true;
  }

  // Asks the app to tap for us (see NativeTap.kt). Unlike the synthetic events above, that touch is
  // trusted, so the viewer can't tell it from a finger.
  function nativeTap() {
    try {
      if (!window.InnerCircleBridge) return false;
      window.InnerCircleBridge.postMessage(JSON.stringify({ type: 'nativeTap', x: TAP_X, y: TAP_Y }));
      return true;
    } catch (e) { return false; }
  }

  // One attempt to reach the next story. A real tap from the app goes first because it behaves exactly
  // like a finger. A page-level "Next" button is only tried second: a selector like [aria-label='Next']
  // can match things that aren't part of the viewer (story-tray arrows, carousels), and clicking one
  // of those looks like a successful attempt while doing nothing. Last resort: the right-arrow key and
  // a scripted tap.
  function advanceStory(rule, attempt) {
    if (attempt === 2) {
      for (var i = 0; i < rule.nextSelectors.length; i++) {
        var btn = document.querySelector(rule.nextSelectors[i]);
        if (btn && visibleRect(btn)) { btn.click(); return 'button'; }
      }
    }
    if (attempt !== 4 && nativeTap()) return 'native tap';
    fireKey('ArrowRight', 'ArrowRight');
    tapRightSide();
    return 'key + scripted tap';
  }

  function endAdSkip() {
    if (adSkip.timer) clearTimeout(adSkip.timer);
    adSkip = { path: null, tries: 0, timer: null };
  }

  function stepAdSkip() {
    adSkip.timer = null;
    var rule = config && config.storyAds;
    if (!rule) return;
    if (location.pathname !== adSkip.path) { // moved on: look at the story we landed on
      endAdSkip();
      applyStoryAds();
      return;
    }
    if (adSkip.tries >= AD_SKIP_MAX_TRIES) {
      console.warn(TAG + ' storyAd: could not get past the sponsored story after ' + adSkip.tries +
        ' tries (selectors stale? re-inspect the story viewer)');
      hideAdCover(); // couldn't skip it, so don't hide the story from the person
      if (config.debug) snapshot('gave-up');
      return; // adSkip.path stays set so this same story isn't retried in a loop
    }
    adSkip.tries++;
    var how = advanceStory(rule, adSkip.tries);
    log('storyAd: attempt ' + adSkip.tries + ' via ' + how);
    adSkip.timer = setTimeout(stepAdSkip, AD_SKIP_STEP_MS);
  }

  // Watching every text and attribute change in the page is only worth it while a story is open.
  function syncStoryObserver(onStories) {
    if (onStories && !storyObserver) {
      storyObserver = new MutationObserver(queueStoryCheck);
      storyObserver.observe(document.documentElement, {
        subtree: true, characterData: true, attributes: true, attributeFilter: ['href', 'aria-label'],
      });
    } else if (!onStories && storyObserver) {
      storyObserver.disconnect();
      storyObserver = null;
    }
  }

  function applyStoryAds() {
    var rule = config.storyAds;
    if (!rule) return;
    var path = location.pathname;
    if (!matches(rule.pathPattern, path)) {
      syncStoryObserver(false);
      endAdSkip(); hideAdCover(); prevAd = { el: null, path: null }; snapPath = null;
      return;
    }
    syncStoryObserver(true);
    if (config.debug && path !== snapPath) { // record every story once it has had a moment to draw
      snapPath = path;
      setTimeout(function () { if (location.pathname === path) snapshot('story'); }, 500);
    }
    if (adSkip.path === path) return; // already skipping (or gave up on) this one
    if (adSkip.path !== null) endAdSkip(); // landed on a different story
    var found = detectStoryAd(rule);
    if (!found) { // an ordinary story, or the ad has finished leaving: the cover can come down
      prevAd = { el: null, path: null };
      hideAdCover();
      return;
    }
    // The ad we just skipped can still be on screen, animating out, when the next story's route
    // has already loaded. It's the very same element, so don't mistake it for a second ad and skip a
    // real story along with it. The cover stays up until that element is gone.
    if (prevAd.el && found.el === prevAd.el && path !== prevAd.path) return;
    prevAd = { el: found.el, path: path };
    adPaths[path] = true;
    adSkip = { path: path, tries: 0, timer: null };
    showAdCover(); // first, so nothing is painted before it
    log('storyAd: sponsored story detected (' + found.kind + '), skipping');
    stepAdSkip();
    if (config.debug) snapshot('ad:' + found.kind, found.el);
  }

  // Story-ad checks that don't wait for the 250ms timer: run on the next frame after a story's text or
  // link/label attributes change in place, before that frame is painted.
  function queueStoryCheck() {
    if (storyCheckQueued || !config || !config.storyAds || !matches(config.storyAds.pathPattern, location.pathname)) return;
    storyCheckQueued = true;
    window.requestAnimationFrame(function () {
      storyCheckQueued = false;
      guard('storyAds', applyStoryAds);
    });
  }

  // --- End of feed ------------------------------------------------------------------------------
  // After "You're all caught up" Instagram goes on with unlabelled "Suggested Posts". Collapsing them
  // makes it load more without end (see InstagramSelectors), so they stay laid out but invisible, and the
  // page is not allowed to scroll past the marker, which also keeps Instagram from loading further.
  function feedItemOf(el) {
    var n = el;
    while (n.parentElement && !n.parentElement.querySelector(':scope > article')) n = n.parentElement;
    return n.parentElement ? n : el;
  }

  function updateEndLimit() {
    if (!caughtUp || !caughtUp.isConnected) { endLimit = Infinity; return; }
    var box = feedItemOf(caughtUp).getBoundingClientRect();
    // Not laid out (a detached or measuring copy): a bogus position must never lock the page in place.
    if (!box.width || !box.height) { endLimit = Infinity; return; }
    endLimit = Math.max(0, box.bottom + window.scrollY - (window.innerHeight - navBottomInset()) + END_PAD);
  }

  function applyEndOfFeed() {
    if (!caughtUp || !caughtUp.isConnected) { caughtUp = null; endLimit = Infinity; return; }
    var posts = document.querySelectorAll('article');
    for (var i = 0; i < posts.length; i++) {
      var post = posts[i];
      if (post.hasAttribute(AFTER_ATTR)) continue;
      if (caughtUp.compareDocumentPosition(post) & Node.DOCUMENT_POSITION_FOLLOWING) {
        post.setAttribute(AFTER_ATTR, '1');
        post.style.setProperty('visibility', 'hidden', 'important');
        post.style.setProperty('pointer-events', 'none', 'important');
      }
    }
    updateEndLimit();
  }

  function onEndScroll() {
    if (endLimit === Infinity) return;
    updateEndLimit();
    if (window.scrollY > endLimit + 1) window.scrollTo(0, endLimit);
  }

  function releaseEndOfFeed() {
    caughtUp = null;
    endLimit = Infinity;
    var held = document.querySelectorAll('[' + AFTER_ATTR + ']');
    for (var i = 0; i < held.length; i++) {
      held[i].removeAttribute(AFTER_ATTR);
      held[i].style.removeProperty('visibility');
      held[i].style.removeProperty('pointer-events');
    }
  }

  // --- Main pass -------------------------------------------------------------------------------
  function applyAll() {
    scheduled = false;
    try {
      var path = location.pathname;
      if (path !== lastPath) {
        var prev = lastPath;
        lastPath = path;
        fullScan = true;
        guard('routeChange', function () { onRouteChange(prev, path); });
      }
      // Do this before any scan or observer work. It keeps a Shorts player from autoplaying or
      // receiving a paging gesture while the subscriptions route is loading.
      if (applyRouteBlocks()) return;
      guard('storyAds', applyStoryAds); // first: an ad must be covered before anything else in this pass delays the frame

      var active = activeHideRules();
      ensureStyle(active);
      var now = Date.now();
      // Counting matches is for diagnostics only, and some selectors (:has) are costly, so not every frame.
      if (now - lastCountAt >= COUNT_LOG_MS) {
        lastCountAt = now;
        active.forEach(function (rule) {
          guard(rule.name, function () {
            reportCount(rule.name, document.querySelectorAll(rule.selector).length);
          });
        });
      }
      if (now - lastFullScan >= FULL_SCAN_MS) fullScan = true;
      var nodes = fullScan ? null : pendingNodes;
      if (fullScan) lastFullScan = now;
      fullScan = false;
      pendingNodes = [];
      if (!nodes || nodes.length) {
        config.textRules.forEach(function (rule) {
          guard(rule.name, function () { applyTextRule(rule, nodes); });
        });
      }
      guard('endOfFeed', applyEndOfFeed);
      guard('explore', tryOpenSearch);
      guard('cover', updateCover);
      guard('scrollLock', applyScrollLocks);
    } catch (e) {
      // Errors inside observer/rAF callbacks escape the try/catch around the initial injection,
      // so they need their own.
      fail('apply pass failed', e);
    }
  }

  // Coalesce bursts of mutations into one pass that runs right before the next paint.
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    window.requestAnimationFrame(applyAll);
  }

  function install(cfg) {
    config = cfg;
    // Instagram is a single-page app: the document survives navigations, so one observer keeps
    // working as the feed grows on scroll and as routes change. childList only: our own
    // attribute/style changes don't retrigger it.
    observer = new MutationObserver(function (records) {
      for (var i = 0; i < records.length && !fullScan; i++) {
        if (records[i].type === 'characterData') { // text edited in place: look at the element that holds it
          var holder = records[i].target.parentElement;
          if (holder) pendingNodes.push(holder);
          continue;
        }
        var added = records[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          var el = n.nodeType === 1 ? n : (n.nodeType === 3 ? n.parentElement : null);
          if (el) pendingNodes.push(el);
        }
        if (pendingNodes.length > MAX_PENDING) { fullScan = true; pendingNodes = []; }
      }
      schedule();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    window.addEventListener('popstate', schedule);
    document.addEventListener('click', onTabClick, true);
    window.addEventListener('scroll', onEndScroll, { passive: true });
    // Instagram updates a story's name and labels by editing text in place, which the observer above
    // (adds/removes only) never sees, so while a story is open it's checked on a timer as well.
    // The same tick also notices a SPA route update which did not happen to add DOM nodes: that
    // matters for a blocked Shorts route, where even a short delay would allow autoplay.
    adPollTimer = setInterval(function () {
      guard('storyAds', applyStoryAds);
      if (config.routeBlocks && config.routeBlocks.length) schedule();
      // The safety-net scan must not wait for some other change to come along and trigger a pass.
      if (Date.now() - lastFullScan >= FULL_SCAN_MS) { fullScan = true; schedule(); }
    }, AD_POLL_MS);
    applyAll();
    log('installed (' + cfg.hideRules.length + ' hide rules, ' + cfg.textRules.length + ' text rules, ' +
        cfg.scrollLocks.length + ' scroll locks)');
  }

  function dispose() {
    if (observer) observer.disconnect();
    observer = null;
    window.removeEventListener('popstate', schedule);
    document.removeEventListener('click', onTabClick, true);
    window.removeEventListener('scroll', onEndScroll);
    releaseEndOfFeed();
    hideCover();
    if (adPollTimer) clearInterval(adPollTimer);
    adPollTimer = null;
    if (storyObserver) storyObserver.disconnect();
    storyObserver = null;
    endAdSkip();
    hideAdCover();
    locks.forEach(releaseLock);
    locks = [];
    if (lockRetryTimer) clearTimeout(lockRetryTimer);
    lockRetryTimer = null;
    var style = document.getElementById(STYLE_ID);
    if (style) style.remove();
    var hidden = document.querySelectorAll('[' + RULE_ATTR + ']');
    for (var i = 0; i < hidden.length; i++) {
      hidden[i].removeAttribute(RULE_ATTR);
      unhideElement(hidden[i]);
    }
  }

  // isStoryAd is for the usage tracker: a story path recognised as an ad isn't a story you viewed.
  window.__innerCircle = {
    install: install,
    dispose: dispose,
    isStoryAd: function (path) { return !!adPaths[path]; },
  };
})();
