package cta.app

/**
 * The recipient equalities questions the public request form asks after a device request is
 * created. Transcribed from the Typeform "Recipient Equalities Data 2025/2026" (form f93D0s88) on
 * 2026-11-02; the dashboard keeps the matching copy in
 * `src/app/views/corewidgets/components/equalities-form/equalities-questions.ts`. The two must
 * change together: a choice the server does not list here is rejected.
 *
 * The answers are special category data. TaDa never stores them: [cta.app.graphql.mutations.EqualitiesMutations]
 * validates them against this list, appends one row to the isolated equalities Google Sheet, and
 * keeps nothing.
 */
data class EqualitiesQuestion(
    /** Stable key the dashboard sends with each answer. */
    val key: String,
    /** Column header in the Sheet. */
    val title: String,
    val choices: List<String>,
    /** Typeform logic: this question is skipped (and must be absent) when [skipWhen] matches. */
    val skipWhen: Pair<String, String>? = null,
)

data class EqualitiesAnswer(
    val key: String,
    val choice: String,
)

/** Raised when the submitted answers do not match the question set. The message never echoes an answer. */
class EqualitiesInvalidAnswersException :
    RuntimeException("Some answers were missing or not recognised. Please reload the page and answer the questions again.") {
    companion object {
        const val CODE = "EQUALITIES_INVALID_ANSWERS"
    }
}

object EqualitiesQuestions {
    val QUESTIONS: List<EqualitiesQuestion> =
        listOf(
            EqualitiesQuestion(
                key = "organisationType",
                title = "What type of organisation are you?",
                choices =
                    listOf(
                        "Campaign and self-advocacy group",
                        "Domestic abuse support",
                        "Education and training",
                        "Food bank",
                        "Heritage and the arts",
                        "Library",
                        "Mental health support",
                        "Prison leavers support",
                        "Refugee support",
                        "Religion",
                        "School",
                        "Social Relief and care",
                        "Sport and recreation",
                        "Support for 65+",
                        "Support for the homeless",
                        "Support for those with disabilities",
                        "Tenants and Residents’ Association",
                        "Women empowerment",
                        "Young carers",
                        "Young People and youth issues",
                    ),
            ),
            EqualitiesQuestion(
                key = "age",
                title = "What is your client's age?",
                choices = listOf("0-10", "11-17", "18-24", "25-34", "35-44", "45-54", "55-64", "65+"),
            ),
            EqualitiesQuestion(
                key = "gender",
                title = "Which of the following best describes your client's gender?",
                choices =
                    listOf(
                        "Male (including trans man)",
                        "Female (including trans woman)",
                        "Prefer not to say",
                        "Non-binary or genderqueer",
                        "Prefer to self-describe",
                    ),
            ),
            EqualitiesQuestion(
                key = "sexualOrientation",
                title = "Which of the following best describes your client's sexual orientation?",
                choices =
                    listOf(
                        "Heterosexual or straight",
                        "Gay or Lesbian",
                        "Bisexual",
                        "Pansexual",
                        "Asexual",
                        "Prefer to self-describe",
                    ),
            ),
            EqualitiesQuestion(
                key = "stateBenefits",
                title = "Does your client receive state benefits?",
                choices = listOf("Yes", "No", "Don't know"),
            ),
            EqualitiesQuestion(
                key = "noRecourseToPublicFunds",
                title = "Is your client No Recourse to Public Funds?",
                choices = listOf("Yes", "No", "Don't know"),
                skipWhen = "stateBenefits" to "Yes",
            ),
            EqualitiesQuestion(
                key = "disability",
                title = "Does your client consider themselves to have a disability or long term health condition?",
                choices = listOf("Yes", "No", "Prefer not to say"),
            ),
            EqualitiesQuestion(
                key = "ethnicity",
                title = "What is your client's race or ethnic group?",
                choices =
                    listOf(
                        "Asian or Asian British",
                        "Black, African, Caribbean or Black British",
                        "Arab or MENA",
                        "Latin American or Hispanic",
                        "White",
                        "Mixed or Multiple ethnic groups",
                        "Other",
                        "Unknown",
                    ),
            ),
            EqualitiesQuestion(
                key = "housing",
                title = "What is your client's housing status?",
                choices =
                    listOf(
                        "Home owner",
                        "Tenant (private housing)",
                        "Tenant (social/council housing)",
                        "Temporary accommodation (including hotels and hostels)",
                        "No fixed address",
                        "Don't know",
                    ),
            ),
            EqualitiesQuestion(
                key = "education",
                title = "Is your client in education?",
                choices = listOf("Yes", "No", "Enrolled but not started", "Don't know"),
            ),
            EqualitiesQuestion(
                key = "working",
                title = "Is your client working?",
                choices =
                    listOf(
                        "Yes - full time",
                        "Yes - part time",
                        "Yes - self employed",
                        "No",
                        "Unable to work (for example due to caring responsibilities, disability, etc.)",
                        "Don't know",
                    ),
            ),
        )

    /** Fixed columns that precede one column per question, in [QUESTIONS] order. */
    val FIXED_COLUMNS: List<String> = listOf("Submitted at", "Correlation ID", "Borough", "Ward")

    /** The header row the Sheet tab must start with. See docs/equalities-sheet.md. */
    val SHEET_HEADER: List<String> get() = FIXED_COLUMNS + QUESTIONS.map { it.title }

    /**
     * Checks [answers] against the question set and returns them keyed by question.
     *
     * Every question must be answered exactly once with one of its listed choices, except a
     * question whose [EqualitiesQuestion.skipWhen] matches, which must be absent.
     */
    fun validate(answers: List<EqualitiesAnswer>): Map<String, String> {
        val byKey = answers.associate { it.key to it.choice }
        if (byKey.size != answers.size) throw EqualitiesInvalidAnswersException()
        val known = QUESTIONS.map { it.key }.toSet()
        if (!known.containsAll(byKey.keys)) throw EqualitiesInvalidAnswersException()

        QUESTIONS.forEach { question ->
            val skipped = question.skipWhen?.let { (key, value) -> byKey[key] == value } ?: false
            val choice = byKey[question.key]
            if (skipped) {
                if (choice != null) throw EqualitiesInvalidAnswersException()
            } else if (choice == null || choice !in question.choices) {
                throw EqualitiesInvalidAnswersException()
            }
        }
        return byKey
    }

    /** One Sheet row, in [SHEET_HEADER] order. A skipped question is an empty cell. */
    fun row(
        submittedAt: String,
        correlationId: Long,
        borough: String?,
        ward: String?,
        answers: Map<String, String>,
    ): List<String> =
        listOf(submittedAt, correlationId.toString(), borough.orEmpty(), ward.orEmpty()) +
            QUESTIONS.map { answers[it.key].orEmpty() }
}
