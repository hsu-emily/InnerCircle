package com.emilyhsu.innercircle.ai

import com.emilyhsu.innercircle.data.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InsightParsingTest {
    private fun parse(s: String) = parseInsight(s, Period.Week)
    private fun recs(s: String) = parse(s).recommendations.map { it.text }

    @Test fun parsesTheFormatTheAppInstructsTheModelToUse() {
        val i = parse(
            """Usage Summary:
You spent 4h 30m on InnerCircle this week, mostly between 9pm and midnight, with the longest sessions near bedtime.

Wellness Recommendations:
1. Try a wind-down routine 30 minutes before bed.
2. Set a morning intention before opening the app.
3. Take a two-minute breathing break at 9pm.""",
        )
        assertEquals(Period.Week, i.period)
        assertEquals("You spent 4h 30m on InnerCircle this week, mostly between 9pm and midnight, with the longest sessions near bedtime.", i.summary)
        assertEquals(
            listOf("Try a wind-down routine 30 minutes before bed.", "Set a morning intention before opening the app.", "Take a two-minute breathing break at 9pm."),
            i.recommendations.map { it.text },
        )
    }

    @Test fun copesWithMarkdownAndTextOnTheSameLineAsTheHeading() {
        // The Playground template itself shows "- **Usage Summary:** [text]".
        val i = parse(
            """- **Usage Summary:** You logged 2h today, peaking at 8pm.
- **Wellness Recommendations:**
1. **Wind down** by 10pm.
2. Keep the phone across the room.""",
        )
        assertEquals("You logged 2h today, peaking at 8pm.", i.summary)
        assertEquals(listOf("Wind down by 10pm.", "Keep the phone across the room."), i.recommendations.map { it.text })
    }

    @Test fun acceptsBulletsAsWellAsNumbers() {
        assertEquals(listOf("One.", "Two.", "Three."), recs("Usage Summary:\nS.\nWellness Recommendations:\n- One.\n* Two.\n• Three."))
        assertEquals(listOf("One.", "Two."), recs("Usage Summary:\nS.\nWellness Recommendations:\n1) One.\n2) Two."))
    }

    @Test fun joinsHardWrappedLinesIntoTheirItem() {
        val i = parse("Usage Summary:\nFirst line\nsecond line of the same paragraph.\n\nSecond paragraph.\n\nWellness Recommendations:\n1. A recommendation that\n   wraps onto a second line.\n2. Short one.")
        assertEquals("First line second line of the same paragraph.\n\nSecond paragraph.", i.summary)
        assertEquals(listOf("A recommendation that wraps onto a second line.", "Short one."), i.recommendations.map { it.text })
    }

    @Test fun anOptionalNoteAfterTheListIsNotARecommendation() {
        assertEquals(
            listOf("Do this.", "Do that."),
            recs("Usage Summary:\nS.\nWellness Recommendations:\n1. Do this.\n2. Do that.\n\nOptional improvement note: add more sessions for better insights."),
        )
    }

    @Test fun isCaseInsensitiveAndHandlesWindowsLineEndings() {
        val i = parse("USAGE SUMMARY:\r\nShort.\r\n\r\nWELLNESS RECOMMENDATION:\r\n1. Rest.\r\n")
        assertEquals("Short.", i.summary)
        assertEquals(listOf("Rest."), i.recommendations.map { it.text })
    }

    @Test fun rebuildsAsMuchAsPossibleWhenTheModelDriftsFromTheFormat() {
        // Recommendations heading missing: still show the summary rather than an error.
        val noRecs = parse("Usage Summary:\nYou used it a little.")
        assertEquals("You used it a little.", noRecs.summary)
        assertTrue(noRecs.recommendations.isEmpty())
        // No headings at all: the whole reply becomes the summary, so nothing useful is thrown away.
        val bare = parse("You mostly scrolled at night. Try putting the phone down at 10pm.")
        assertEquals("You mostly scrolled at night. Try putting the phone down at 10pm.", bare.summary)
        // Recommendations given as a paragraph with no list markers: one item.
        assertEquals(listOf("Take breaks at 9pm and keep mornings screen-free."), recs("Usage Summary:\nS.\nWellness Recommendations:\nTake breaks at 9pm and keep mornings screen-free."))
    }

    @Test fun emptyAnswersAreRejected() {
        for (blank in listOf("", "   ", "\n\n", "Usage Summary:", "Usage Summary:\n\nWellness Recommendations:\n1. Only recs")) {
            try {
                parse(blank)
                fail("should have rejected: '$blank'")
            } catch (e: AiException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test fun sizesAreCappedSoTheCardCannotBlowUp() {
        val many = (1..8).joinToString("\n") { "$it. Recommendation number $it." }
        assertEquals(5, recs("Usage Summary:\nS.\nWellness Recommendations:\n$many").size)
        val long = "x".repeat(2_000)
        assertEquals(400, recs("Usage Summary:\nS.\nWellness Recommendations:\n1. $long").single().length)
        assertEquals(1_500, parse("Usage Summary:\n$long\nWellness Recommendations:\n1. a").summary.length)
    }

    @Test fun serviceErrorsAreActionable() {
        assertTrue("tomorrow" in describeServiceError(429, """{"error":"rate_limited","scope":"install"}"""))
        assertTrue("busy" in describeServiceError(429, """{"error":"rate_limited","scope":"global"}"""))
        assertTrue("update" in describeServiceError(401, ""))
        assertTrue("busy" in describeServiceError(503, ""))
        assertTrue("problems" in describeServiceError(502, ""))
        assertTrue("problems" in describeServiceError(500, "<html>oops</html>"))
        assertTrue("isn't available" in describeServiceError(404, ""))
    }
}
