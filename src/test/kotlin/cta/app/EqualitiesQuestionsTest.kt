package cta.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The answer rules submitEqualities enforces before anything is written to the Sheet. */
class EqualitiesQuestionsTest {
    private fun answers(benefits: String = "No"): MutableList<EqualitiesAnswer> =
        EqualitiesQuestions.QUESTIONS
            .filterNot { benefits == "Yes" && it.key == "noRecourseToPublicFunds" }
            .map { EqualitiesAnswer(it.key, if (it.key == "stateBenefits") benefits else it.choices.first()) }
            .toMutableList()

    @Test
    fun `a full set of listed choices is accepted`() {
        val result = EqualitiesQuestions.validate(answers())
        assertEquals(11, result.size)
    }

    @Test
    fun `benefits Yes skips the NRPF question, which must then be absent`() {
        assertEquals(10, EqualitiesQuestions.validate(answers(benefits = "Yes")).size)

        val withNrpf = answers(benefits = "Yes").apply { add(EqualitiesAnswer("noRecourseToPublicFunds", "No")) }
        assertThrows<EqualitiesInvalidAnswersException> { EqualitiesQuestions.validate(withNrpf) }
    }

    @Test
    fun `benefits No still requires the NRPF question`() {
        val missing = answers().filterNot { it.key == "noRecourseToPublicFunds" }
        assertThrows<EqualitiesInvalidAnswersException> { EqualitiesQuestions.validate(missing) }
    }

    @Test
    fun `a missing, unknown, duplicated or unlisted answer is rejected`() {
        assertThrows<EqualitiesInvalidAnswersException> { EqualitiesQuestions.validate(answers().drop(1)) }
        assertThrows<EqualitiesInvalidAnswersException> {
            EqualitiesQuestions.validate(answers().apply { add(EqualitiesAnswer("favouriteColour", "Blue")) })
        }
        assertThrows<EqualitiesInvalidAnswersException> {
            EqualitiesQuestions.validate(answers().apply { add(EqualitiesAnswer("age", "18-24")) })
        }
        assertThrows<EqualitiesInvalidAnswersException> {
            EqualitiesQuestions.validate(answers().map { if (it.key == "age") EqualitiesAnswer("age", "SECRET-VALUE") else it })
        }
    }

    @Test
    fun `the refusal never quotes an answer`() {
        val ex =
            assertThrows<EqualitiesInvalidAnswersException> {
                EqualitiesQuestions.validate(answers().map { if (it.key == "age") EqualitiesAnswer("age", "SECRET-VALUE") else it })
            }
        assertFalse(ex.message!!.contains("SECRET-VALUE"))
    }

    @Test
    fun `a row lines up with the header, with an empty cell for a skipped question`() {
        val validated = EqualitiesQuestions.validate(answers(benefits = "Yes"))
        val row = EqualitiesQuestions.row("2026-11-16 10:00:00", 42L, "Lambeth", null, validated)

        val header = EqualitiesQuestions.SHEET_HEADER
        assertEquals(header.size, row.size)
        assertEquals(listOf("2026-11-16 10:00:00", "42", "Lambeth", ""), row.take(4))
        assertEquals("", row[header.indexOf("Is your client No Recourse to Public Funds?")])
        assertEquals("Yes", row[header.indexOf("Does your client receive state benefits?")])
    }
}
