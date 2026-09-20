package com.emilyhsu.innercircle.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import com.emilyhsu.innercircle.ui.theme.Ic
import com.emilyhsu.innercircle.ui.components.PrimaryButton
import com.emilyhsu.innercircle.ui.components.InnerCircleLogo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.emilyhsu.innercircle.data.SocialApp

private const val TAG = "InnerCircleWeb"
private const val JS_TAG = "InnerCircleJS"
private const val MIN_TAP_GAP_MS = 300L

private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/130.0.0.0 Mobile Safari/537.36"

/**
 * A supported platform's mobile website in a WebView, with [FeedCleaner] injected on every page load.
 *
 * [onPageMessage] receives the JSON usage deltas posted by assets/usage_tracker.js. They arrive
 * through a WebMessageListener limited to that platform's own origins, so other sites and frames can't
 * reach it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlatformWebView(
    app: SocialApp,
    modifier: Modifier = Modifier,
    startUrl: String = "https://www.instagram.com/",
    onPageMessage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val cleanerScript = remember(app) { FeedCleaner.buildScript(context, app) }
    val latestOnPageMessage by rememberUpdatedState(onPageMessage)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<LoadError?>(null) }
    // True once a page has actually appeared. Until then (WebView start-up can take seconds on slow
    // devices) the screen would be plain white, which looks broken, so a loading state covers it.
    var contentShown by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // chrome://inspect on desktop, debug builds only.
                if (ctx.isDebuggable()) WebView.setWebContentsDebuggingEnabled(true)

                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = mobileChromeUserAgent(ctx)

                    webViewClient = CleaningWebViewClient(
                        app = app,
                        cleanerScript = cleanerScript,
                        onHistoryChanged = { canGoBack = it.canGoBack() },
                        onLoadError = { loadError = it },
                        onContentVisible = { contentShown = true },
                    )
                    webChromeClient = LoggingWebChromeClient()

                    // Must be registered before loading so `window.InnerCircleBridge` exists on the first page.
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        var lastTapAt = 0L
                        WebViewCompat.addWebMessageListener(this, "InnerCircleBridge", bridgeOrigins(app)) { view, message, _, isMainFrame, _ ->
                            val data = message.data ?: return@addWebMessageListener
                            val tap = parseTapRequest(data)
                            if (tap == null) {
                                latestOnPageMessage(data) // usage counts
                                return@addWebMessageListener
                            }
                            // Taps are only for the story viewer, and never faster than a person could.
                            val now = SystemClock.uptimeMillis()
                            val inStories = app == SocialApp.Instagram && Uri.parse(view.url).path?.startsWith("/stories/") == true
                            if (isMainFrame && inStories && now - lastTapAt >= MIN_TAP_GAP_MS) {
                                lastTapAt = now
                                view.tap(tap)
                            }
                        }
                    } else {
                        Log.w(TAG, "WebMessageListener unsupported; usage counts (posts/stories/scroll) will not be recorded")
                    }

                    loadUrl(startUrl)
                    webView = this
                }
            },
            onRelease = { it.destroy() },
        )
        if (!contentShown && loadError == null) LoadingOverlay()
        loadError?.let { error ->
            LoadErrorOverlay(error, onRetry = {
                loadError = null
                val view = webView ?: return@LoadErrorOverlay
                // If nothing ever loaded, reload() has nothing to reload; start from the beginning.
                if (view.url.isNullOrBlank() || view.url == "about:blank") view.loadUrl(startUrl) else view.reload()
            })
        }
    }
}

/** What went wrong loading a page, in words a person can act on, plus the raw code for troubleshooting. */
data class LoadError(val message: String, val technical: String)

/** Maps a WebView error code to a plain-language message. */
internal fun describeLoadError(errorCode: Int, app: SocialApp = SocialApp.Instagram): String = when (errorCode) {
    WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO,
    WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_PROXY_AUTHENTICATION ->
        "Can't reach ${app.displayName}. Check your internet connection, then try again."
    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
        "Couldn't make a secure connection to ${app.displayName}. Check your phone's date and time, then try again."
    else -> "${app.displayName} couldn't load. Try again in a moment."
}

/** Web pages may only report usage from the platform currently open in this WebView. */
private fun bridgeOrigins(app: SocialApp): Set<String> = when (app) {
    SocialApp.Instagram -> setOf("https://www.instagram.com")
    // YouTube can canonicalize a mobile navigation to www, so accept both first-party origins.
    SocialApp.YouTube -> setOf("https://m.youtube.com", "https://www.youtube.com")
    else -> emptySet()
}

/** Shown from launch until the first page appears. */
@Composable
private fun LoadingOverlay() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ic.Background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InnerCircleLogo(size = 56.dp)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(Modifier.size(22.dp), color = Ic.Ink, strokeWidth = 2.dp)
    }
}

/** Covers the blank page a failed load would otherwise leave behind. */
@Composable
private fun LoadErrorOverlay(error: LoadError, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ic.Background)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InnerCircleLogo(size = 56.dp)
        Spacer(Modifier.height(24.dp))
        Text("Something's not loading", style = MaterialTheme.typography.headlineSmall, color = Ic.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(error.message, style = MaterialTheme.typography.bodyLarge, color = Ic.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth(0.7f))
        Spacer(Modifier.height(16.dp))
        Text(error.technical, style = MaterialTheme.typography.labelSmall, color = Ic.DisabledText, textAlign = TextAlign.Center)
    }
}

private class CleaningWebViewClient(
    private val app: SocialApp,
    private val cleanerScript: String,
    private val onHistoryChanged: (WebView) -> Unit,
    private val onLoadError: (LoadError?) -> Unit,
    private val onContentVisible: () -> Unit,
) : WebViewClient() {

    /** A failed load still ends with onPageFinished; remember it so that doesn't clear the error. */
    private var failedThisLoad = false

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        failedThisLoad = false
        Log.d(TAG, "onPageStarted: $url")
    }

    // Fires for full page loads. Later in-app (pushState) navigations don't need it: the
    // MutationObserver installed here lives on the same document and keeps running.
    override fun onPageCommitVisible(view: WebView, url: String?) {
        if (!failedThisLoad) onContentVisible()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (failedThisLoad) return
        onLoadError(null)
        onContentVisible()
        Log.d(TAG, "onPageFinished: $url, injecting cleaner")
        view.evaluateJavascript(cleanerScript, null)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        onHistoryChanged(view)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        failedThisLoad = true
        Log.e(TAG, "Load error ${error.errorCode} (${error.description}) for ${request.url}")
        onLoadError(LoadError(describeLoadError(error.errorCode, app), error.description.toString()))
    }
}

/**
 * Forwards the page's console.* output (including our injected script's) to Logcat, and suppresses
 * WebView's built-in video poster.
 */
private class LoggingWebChromeClient : WebChromeClient() {

    // Android WebView paints its own poster (a grey field with a huge play icon) over every <video>
    // that has no poster of its own until the first frame arrives. Instagram's reels have none, so
    // opening one flashed a giant play button for about a second. A transparent 1x1 bitmap
    // replaces it, leaving the page's own dark background and loading skeleton visible instead.
    override fun getDefaultVideoPoster(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val text = "${message.message()}  (${message.sourceId()}:${message.lineNumber()})"
        when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.e(JS_TAG, text)
            ConsoleMessage.MessageLevel.WARNING -> Log.w(JS_TAG, text)
            else -> Log.d(JS_TAG, text)
        }
        return true
    }
}

private fun Context.isDebuggable(): Boolean =
    applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/**
 * The device WebView's own UA with the WebView-only markers stripped, so it reads as regular mobile
 * Chrome and stays in sync with the installed WebView version. Falls back to a fixed string.
 */
private fun mobileChromeUserAgent(context: Context): String {
    val chromeLike = WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace("Version/4.0 ", "")
    return if ("Chrome/" in chromeLike) chromeLike else FALLBACK_USER_AGENT
}
