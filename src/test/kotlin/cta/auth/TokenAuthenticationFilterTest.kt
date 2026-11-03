package cta.auth

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.ObjectMapper

class TokenAuthenticationFilterTest {
    private val authService =
        AuthService().also {
            ReflectionTestUtils.setField(it, "adminHeader", "X-Auth-Admin-Secret")
            ReflectionTestUtils.setField(it, "secret", "s3cret")
        }
    private val filter = TokenAuthenticationFilter(authService, ObjectMapper())

    private val adminSecretLogger = LoggerFactory.getLogger("cta.auth.admin-secret") as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }

    init {
        adminSecretLogger.addAppender(appender)
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
        adminSecretLogger.detachAppender(appender)
    }

    @Test
    fun `every use of the admin secret is logged, without the secret`() {
        val request = MockHttpServletRequest("POST", "/graphql")
        request.addHeader("X-Auth-Admin-Secret", "s3cret")

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val messages = appender.list.map { it.formattedMessage }
        assertEquals(listOf("Request authenticated with the admin secret: POST /graphql"), messages)
        assertTrue(messages.none { it.contains("s3cret") })
    }

    @Test
    fun `requests without the admin secret are not logged as admin use`() {
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), MockFilterChain())
        assertTrue(appender.list.isEmpty())
    }

    @Test
    fun `invalid admin token halts the request with a 401 json response`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Auth-Admin-Secret", "wrong")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("INVALID_ADMIN_TOKEN"))
        assertNull(chain.request, "the filter chain must not continue after an invalid token")
    }

    @Test
    fun `valid admin token authenticates and continues the chain`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Auth-Admin-Secret", "s3cret")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNotNull(chain.request, "the filter chain must continue for a valid token")
        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertTrue(authentication!!.authorities.any { it.authority == "write:organisations" })
    }

    @Test
    fun `absent header continues the chain anonymously`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNotNull(chain.request, "the filter chain must continue without the header")
        assertNull(SecurityContextHolder.getContext().authentication)
    }
}
