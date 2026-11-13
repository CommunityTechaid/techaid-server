package cta.app.config

/**
 * A short ref the dashboard mints per GraphQL request and shows staff alongside an error, so a
 * reported error can be found in App Insights `AppTraces` (#371). Optional: Apps Script and other
 * callers never send it, and a request without it behaves exactly as before.
 */
const val CLIENT_ERROR_REF_HEADER = "X-CTA-Ref"

/** GraphQL-context key carrying the validated ref from the interceptor to the exception resolver. */
const val CLIENT_ERROR_REF_CONTEXT_KEY = "cta.clientErrorRef"

/** What the error log line shows when a ref was sent but failed validation. */
const val INVALID_CLIENT_ERROR_REF = "invalid"

/**
 * The header is attacker-controlled and goes into log lines and responses, so only this strict
 * shape is accepted. Anything else (too long, other characters, CR/LF that could forge a log line)
 * is dropped and never logged or returned verbatim.
 */
private val CLIENT_ERROR_REF_PATTERN = Regex("^[A-Za-z0-9-]{1,32}$")

/** Returns [raw] if it is a well-formed ref, otherwise null. */
fun validClientErrorRef(raw: String?): String? = raw?.takeIf { CLIENT_ERROR_REF_PATTERN.matches(it) }
