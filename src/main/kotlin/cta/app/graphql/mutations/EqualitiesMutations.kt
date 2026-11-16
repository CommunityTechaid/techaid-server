package cta.app.graphql.mutations

import cta.app.DeviceRequestRepository
import cta.app.EqualitiesAnswer
import cta.app.EqualitiesInvalidAnswersException
import cta.app.EqualitiesQuestions
import cta.app.services.BookingRateLimiter
import cta.app.services.DeviceRequestService
import cta.app.services.EqualitiesSheetUnavailableException
import cta.app.services.EqualitiesSheetWriter
import cta.app.services.PublicCreateThrottle
import cta.toNullable
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ControllerAdvice
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

data class EqualitiesAnswerInput(
    val key: String,
    val choice: String,
)

data class SubmitEqualitiesInput(
    val correlationId: String,
    val ward: String? = null,
    val answers: List<EqualitiesAnswerInput>,
)

/**
 * The correlation id is unknown: the request already finished this step, was declined by the
 * 20-minute sweep, or never existed. Nothing was written to the Sheet.
 */
class EqualitiesRequestNotFoundException :
    RuntimeException(
        "We couldn't match these answers to your request. It may have timed out - " +
            "please contact distributions@communitytechaid.org.uk.",
    ) {
    companion object {
        const val CODE = "EQUALITIES_REQUEST_NOT_FOUND"
    }
}

/**
 * The native replacement for the equalities Typeform (f93D0s88) on the public request form.
 *
 * Anonymous, like createDeviceRequest: the referee holds the request's random correlationId,
 * which is what Typeform used to carry as its hidden `corr_id`. Order matters:
 *
 * 1. the request must still be waiting for this step (correlationId set);
 * 2. the answers must match [EqualitiesQuestions];
 * 3. the row is appended to the isolated Google Sheet;
 * 4. only then is the request completed and the acknowledgement email sent.
 *
 * If 3 fails nothing changes, so the form can offer a retry with the answers still on screen.
 * TaDa keeps none of the answers - not in the database and not in any log. GraphQlTelemetryConfig
 * redacts this operation's variables and error messages.
 *
 * Not gated on the `native-equalities-form` flag: the flag picks which form the dashboard shows,
 * and someone who loaded the native form just before the flag was switched off must still be able
 * to submit it.
 */
@Controller
class EqualitiesMutations(
    private val deviceRequests: DeviceRequestRepository,
    private val deviceRequestService: DeviceRequestService,
    private val sheetWriter: EqualitiesSheetWriter,
    private val publicCreateThrottle: PublicCreateThrottle,
    @Qualifier("publicEqualitiesRateLimiter") private val rateLimiter: BookingRateLimiter,
) {
    @MutationMapping
    @Transactional
    fun submitEqualities(
        @Argument data: SubmitEqualitiesInput,
    ): Boolean {
        publicCreateThrottle.acquireOrThrow(rateLimiter)

        val correlationId = data.correlationId.trim().toLongOrNull() ?: throw EqualitiesRequestNotFoundException()
        val request = deviceRequests.findByCorrelationId(correlationId).toNullable() ?: throw EqualitiesRequestNotFoundException()

        val answers = EqualitiesQuestions.validate(data.answers.map { EqualitiesAnswer(it.key, it.choice) })
        val ward =
            data.ward
                ?.trim()
                ?.take(MAX_WARD_LENGTH)
                ?.ifBlank { null }

        sheetWriter.append(
            EqualitiesQuestions.row(
                submittedAt = ZonedDateTime.now(LONDON).format(SUBMITTED_AT_FORMAT),
                correlationId = correlationId,
                borough = request.borough,
                ward = ward,
                answers = answers,
            ),
        )

        val completed = deviceRequestService.completeEqualitiesStep(request)
        logger.info("Equalities answers recorded for device request ${completed.id}")
        deviceRequestService.acknowledgeSubmission(completed)
        return true
    }

    companion object {
        private const val MAX_WARD_LENGTH = 100
        private val LONDON = ZoneId.of("Europe/London")
        private val SUBMITTED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

/** Each refusal carries a stable `extensions.code` so the form never matches on wording. */
@ControllerAdvice
class EqualitiesExceptionHandler {
    @GraphQlExceptionHandler
    fun handle(ex: EqualitiesRequestNotFoundException): GraphQLError = error(ex.message, EqualitiesRequestNotFoundException.CODE)

    @GraphQlExceptionHandler
    fun handle(ex: EqualitiesInvalidAnswersException): GraphQLError = error(ex.message, EqualitiesInvalidAnswersException.CODE)

    @GraphQlExceptionHandler
    fun handle(ex: EqualitiesSheetUnavailableException): GraphQLError =
        error(ex.message, EqualitiesSheetUnavailableException.CODE, ErrorType.INTERNAL_ERROR)

    private fun error(
        message: String?,
        code: String,
        type: ErrorType = ErrorType.BAD_REQUEST,
    ): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(type)
            .message(message)
            .extensions(mapOf("code" to code))
            .build()
}
