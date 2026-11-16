package cta.app.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

private val logger = KotlinLogging.logger {}

/** Where equalities answers go. Exists as an interface so tests can swap in a fake. */
interface EqualitiesSheetWriter {
    /** Appends one row. Throws [EqualitiesSheetUnavailableException] when the row was not written. */
    fun append(row: List<String>)
}

/**
 * The Sheet could not be written (not configured, Google refused, or timed out). The caller has
 * NOT been marked complete, so the form shows a retry with the answers still on screen. The
 * message is shown to the public and never contains an answer.
 */
class EqualitiesSheetUnavailableException : RuntimeException("We couldn't save your answers just now. Please try again in a minute.") {
    companion object {
        const val CODE = "EQUALITIES_SHEET_UNAVAILABLE"
    }
}

/**
 * Appends equalities rows to the isolated Google Sheet with the Sheets API, as a Google service
 * account that has Editor access to that one spreadsheet and nothing else. UAT and production
 * use different service accounts and spreadsheets (see docs/equalities-sheet.md).
 *
 * Calls the REST endpoint directly rather than adding the Sheets client library: one request, and
 * no client-side HTTP logging that could ever print a row. Nothing here logs the row or Google's
 * response body - only the HTTP status.
 */
@Component
class GoogleSheetsEqualitiesWriter(
    private val objectMapper: ObjectMapper,
    @Value("\${equalities.sheet.spreadsheet-id:}") private val spreadsheetId: String,
    @Value("\${equalities.sheet.tab:TaDa responses}") private val tab: String,
    @Value("\${equalities.sheet.service-account-key:}") private val serviceAccountKey: String,
    @Value("\${equalities.sheet.timeout-seconds:15}") private val timeoutSeconds: Long,
) : EqualitiesSheetWriter {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build()

    private val credentials: GoogleCredentials? by lazy {
        if (serviceAccountKey.isBlank()) {
            null
        } else {
            runCatching {
                ServiceAccountCredentials.fromStream(decodeKey(serviceAccountKey).byteInputStream()).createScoped(SCOPE)
            }.onFailure { logger.error("Equalities Sheet: the service account key could not be read (${it.javaClass.simpleName})") }
                .getOrNull()
        }
    }

    override fun append(row: List<String>) {
        val creds = credentials
        if (spreadsheetId.isBlank() || creds == null) {
            logger.warn("Equalities Sheet: not configured (EQUALITIES_SHEET_ID / EQUALITIES_SA_KEY), row not written")
            throw EqualitiesSheetUnavailableException()
        }
        val status =
            try {
                creds.refreshIfExpired()
                val token = creds.accessToken?.tokenValue ?: throw IllegalStateException("No access token")
                val request =
                    HttpRequest
                        .newBuilder(appendUri())
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(mapOf("values" to listOf(row)))))
                        .build()
                http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            } catch (e: Exception) {
                // Exception type only: a message could quote the request.
                logger.warn("Equalities Sheet: append failed (${e.javaClass.simpleName})")
                throw EqualitiesSheetUnavailableException()
            }
        if (status !in 200..299) {
            logger.warn("Equalities Sheet: append returned HTTP $status")
            throw EqualitiesSheetUnavailableException()
        }
    }

    /**
     * RAW stops a cell being read as a formula; INSERT_ROWS never overwrites. The range names the
     * tab only, so the API appends after the last row of the table that starts in A1.
     */
    private fun appendUri(): URI {
        val range = URLEncoder.encode("'$tab'!A1", Charsets.UTF_8).replace("+", "%20")
        return URI.create(
            "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range:append" +
                "?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
        )
    }

    companion object {
        private val SCOPE = listOf("https://www.googleapis.com/auth/spreadsheets")

        /** The key JSON as downloaded from Google, or the same base64-encoded (easier to paste into a secret). */
        fun decodeKey(value: String): String {
            val trimmed = value.trim()
            return if (trimmed.startsWith("{")) trimmed else String(Base64.getDecoder().decode(trimmed), Charsets.UTF_8)
        }
    }
}
