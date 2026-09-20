package com.emilyhsu.innercircle.ai

import com.emilyhsu.innercircle.data.Insight
import com.emilyhsu.innercircle.data.Period
import com.emilyhsu.innercircle.data.PeriodSummary
import com.emilyhsu.innercircle.data.Profile
import com.emilyhsu.innercircle.data.Recommendation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Something the UI can show to the user as-is. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Asks InnerCircle's insights server for a summary. There is no API key here: the server holds the
 * company's OpenAI key, adds the instructions, and enforces daily limits. This client only sends the
 * write-up of the survey answers and usage totals ([InsightPrompt.user]) and an anonymous random
 * install id that the server uses to count each install's daily allowance.
 *
 * Contract with server/insights-worker: POST `{"prompt": "..."}` with header `X-Install-Id`;
 * 200 -> `{"content": "<the model's text>"}` (see [parseInsight]); errors -> `{"error": "<code>", "scope"?: "..."}`.
 */
class InsightClient(
    private val endpoint: () -> String,
    private val installId: () -> String,
) {
    suspend fun generateInsight(profile: Profile, summary: PeriodSummary): Insight = withContext(Dispatchers.IO) {
        val url = endpoint().trim()
        if (url.isEmpty()) throw AiException("AI insights aren't set up in this version yet.")
        val body = JSONObject().put("prompt", InsightPrompt.user(profile, summary))
        parseInsight(post(url, body), summary.period)
    }

    private fun post(url: String, body: JSONObject): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Install-Id", installId())
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AiException(describeServiceError(code, text))
            return JSONObject(text).getString("content")
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException("Couldn't reach the insights service. Check your connection and try again.", e)
        } catch (e: Exception) {
            throw AiException("The insights service sent a response InnerCircle couldn't read.", e)
        } finally {
            conn.disconnect()
        }
    }
}

/** Turns the server's error into something a person can act on. */
internal fun describeServiceError(code: Int, body: String): String {
    val scope = runCatching { JSONObject(body).optString("scope") }.getOrNull().orEmpty()
    return when (code) {
        429 ->
            if (scope == "global") "Insights are very busy right now. Please try again a little later."
            else "You've used today's insights. More will be available tomorrow."
        401, 403 -> "This version of InnerCircle can't reach the insights service. Please update the app."
        400, 413, 415 -> "Couldn't prepare your data for insights."
        404 -> "The insights service isn't available right now."
        503 -> "The insights service is busy. Try again in a moment."
        else -> "The insights service is having problems. Try again in a moment."
    }
}

/**
 * Reads the model's reply, which follows the prompt's format:
 *
 *     Usage Summary:
 *     <paragraphs>
 *
 *     Wellness Recommendations:
 *     1. <text>
 *     2. <text>
 *
 * It copes with the ways models drift from that: markdown bold on the headings, headings and text on
 * the same line, bullets instead of numbers, and a trailing note. If it can't find the headings at all
 * it shows the whole reply as the summary rather than throwing away something useful.
 */
internal fun parseInsight(content: String, period: Period): Insight {
    val text = stripMarkdown(content).trim()
    if (text.isEmpty()) throw AiException("The AI's answer was empty. Try again.")

    val summaryHeading = Regex("usage summary\\s*:?", RegexOption.IGNORE_CASE).find(text)
    val recsHeading = Regex("wellness recommendations?\\s*:?", RegexOption.IGNORE_CASE)
        .find(text, summaryHeading?.range?.last?.plus(1) ?: 0)

    val summaryText = when {
        summaryHeading != null && recsHeading != null -> text.substring(summaryHeading.range.last + 1, recsHeading.range.first)
        summaryHeading != null -> text.substring(summaryHeading.range.last + 1)
        recsHeading != null -> text.substring(0, recsHeading.range.first)
        else -> text
    }
    val recsText = recsHeading?.let { text.substring(it.range.last + 1) }.orEmpty()

    // If the next heading was written as a bullet ("- Wellness Recommendations:"), its "-" is left on the end of the summary.
    val summary = paragraphs(summaryText.replace(Regex("\\s+[-*•]\\s*$"), "")).take(MAX_SUMMARY_CHARS)
    if (summary.isEmpty()) throw AiException("The AI's answer wasn't in the expected format. Try again.")
    return Insight(
        period = period,
        summary = summary,
        recommendations = parseRecommendations(recsText),
        generatedAtMs = System.currentTimeMillis(),
    )
}

private const val MAX_SUMMARY_CHARS = 1_500
private const val MAX_RECOMMENDATIONS = 5
private const val MAX_RECOMMENDATION_CHARS = 400

/** The card is plain text, so drop the markdown models like to add. */
private fun stripMarkdown(s: String): String = s
    .replace("\r\n", "\n")
    .replace("**", "").replace("__", "").replace("`", "")
    .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")

/** Keeps paragraph breaks, joins the hard-wrapped lines inside each paragraph. */
private fun paragraphs(s: String): String =
    s.trim().split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s*\\n\\s*"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

private val listMarker = Regex("^\\s*(?:\\d+[.)]|[-*•])\\s+(.*)$")
private val trailingNote = Regex("^\\s*(?:optional\\s+)?(?:improvement\\s+)?note\\b", RegexOption.IGNORE_CASE)

private fun parseRecommendations(text: String): List<Recommendation> {
    val items = mutableListOf<StringBuilder>()
    for (line in text.lines()) {
        if (line.isBlank()) continue
        if (trailingNote.containsMatchIn(line)) break            // an optional note after the list isn't a recommendation
        val marker = listMarker.find(line)
        when {
            marker != null -> items += StringBuilder(marker.groupValues[1].trim())
            items.isEmpty() -> items += StringBuilder(line.trim())  // text with no list marker at all
            else -> items.last().append(' ').append(line.trim())    // a wrapped continuation of the previous item
        }
    }
    return items.map { it.toString().trim().take(MAX_RECOMMENDATION_CHARS) }
        .filter { it.isNotEmpty() }
        .take(MAX_RECOMMENDATIONS)
        .map { Recommendation(it) }
}
