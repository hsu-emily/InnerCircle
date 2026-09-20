package com.emilyhsu.innercircle.ai

import com.emilyhsu.innercircle.data.AppDay
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodSummary
import com.emilyhsu.innercircle.data.Profile
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.LocalDate

/** Exercises the real HTTP path against a local stand-in for InnerCircle's insights server. */
class InsightClientTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var responseBody = ""
    private var hits = 0
    private var seenPath: String? = null
    private var seenInstall: String? = null
    private var seenAuth: String? = null
    private var seenBody: JSONObject? = null

    private val endpoint get() = "http://127.0.0.1:${server.address.port}/v1/insight"
    private val today = LocalDate.of(2026, 9, 19)
    private val summary = PeriodSummary(Period.Day, today, today, AppDay(totalMs = 600_000), 0, emptyMap(), listOf(today to 600_000L))
    private val profile = Profile(completed = true, goals = setOf("Cut down on doom scrolling"))

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                hits++
                seenPath = ex.requestURI.path
                seenInstall = ex.requestHeaders.getFirst("X-Install-Id")
                seenAuth = ex.requestHeaders.getFirst("Authorization")
                seenBody = JSONObject(ex.requestBody.readBytes().decodeToString())
                val bytes = responseBody.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @After fun stop() = server.stop(0)

    private fun client(url: String = endpoint, install: String = "3f2b8c1e-9d4a-4e7b-8a15-0c6d2e9f7a41") =
        InsightClient(endpoint = { url }, installId = { install })

    private fun envelope(content: String) = JSONObject().put("content", content).toString()

    private fun failure(block: () -> Unit): AiException {
        try { block() } catch (e: AiException) { return e }
        fail("expected an AiException"); throw IllegalStateException()
    }

    @Test fun sendsOnlyThePromptAndAnAnonymousIdAndParsesTheAnswer() {
        responseBody = envelope("Usage Summary:\nMostly evenings.\n\nWellness Recommendations:\n1. Stop at 10pm.")
        val insight = runBlocking { client().generateInsight(profile, summary) }

        assertEquals("Mostly evenings.", insight.summary)
        assertEquals("Stop at 10pm.", insight.recommendations.single().text)
        assertEquals("/v1/insight", seenPath)
        assertEquals("3f2b8c1e-9d4a-4e7b-8a15-0c6d2e9f7a41", seenInstall)
        // The server owns the model, limits and instructions: the app sends nothing but the write-up.
        assertEquals(setOf("prompt"), seenBody!!.keys().asSequence().toSet())
        assertTrue("Cut down on doom scrolling" in seenBody!!.getString("prompt"))
        assertFalse("the app must never send credentials", seenAuth != null)
    }

    @Test fun notConfiguredSaysSoWithoutCallingAnything() {
        val e = failure { runBlocking { client(url = "  ").generateInsight(profile, summary) } }
        assertTrue("aren't set up" in e.message!!)
        assertEquals(0, hits)
    }

    @Test fun dailyLimitAndBusyServerAreExplainedDifferently() {
        status = 429; responseBody = """{"error":"rate_limited","scope":"install"}"""
        assertTrue("tomorrow" in failure { runBlocking { client().generateInsight(profile, summary) } }.message!!)
        responseBody = """{"error":"rate_limited","scope":"global"}"""
        assertTrue("busy" in failure { runBlocking { client().generateInsight(profile, summary) } }.message!!)
        responseBody = """{"error":"rate_limited","scope":"ip"}"""
        assertTrue("tomorrow" in failure { runBlocking { client().generateInsight(profile, summary) } }.message!!)
    }

    @Test fun serverProblemsAreFriendlyAndLeakNothing() {
        status = 502; responseBody = """{"error":"service_unavailable"}"""
        val e = failure { runBlocking { client().generateInsight(profile, summary) } }
        assertTrue("problems" in e.message!!)
        assertFalse("service_unavailable" in e.message!!)
        status = 401; responseBody = """{"error":"bad_install_id"}"""
        assertTrue("update" in failure { runBlocking { client().generateInsight(profile, summary) } }.message!!)
    }

    @Test fun garbageFromTheServerIsAFriendlyError() {
        status = 200; responseBody = "<html>captive portal</html>"
        assertTrue("couldn't read" in failure { runBlocking { client().generateInsight(profile, summary) } }.message!!)
        responseBody = envelope("   ")
        assertTrue("empty" in failure { runBlocking { client().generateInsight(profile, summary) } }.message!!)
    }

    @Test fun unreachableServerIsAFriendlyError() {
        val dead = client(url = "http://127.0.0.1:1/v1/insight")
        assertTrue("reach the insights service" in failure { runBlocking { dead.generateInsight(profile, summary) } }.message!!)
    }
}
