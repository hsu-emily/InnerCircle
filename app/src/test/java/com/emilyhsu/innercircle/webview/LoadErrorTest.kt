package com.emilyhsu.innercircle.webview

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadErrorTest {
    @Test fun connectionProblemsTellThePersonToCheckTheirInternet() {
        listOf(
            WebViewClient.ERROR_HOST_LOOKUP,          // the DNS failure that leaves a blank screen
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO, WebViewClient.ERROR_TIMEOUT,
        ).forEach { code ->
            val message = describeLoadError(code)
            assertTrue("code $code -> $message", "internet connection" in message)
        }
    }

    @Test fun aWrongClockGetsItsOwnHint() {
        assertTrue("date and time" in describeLoadError(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE))
    }

    @Test fun anythingElseGetsAGenericRetryMessage() {
        assertEquals("Instagram couldn't load. Try again in a moment.", describeLoadError(WebViewClient.ERROR_UNKNOWN))
    }
}
