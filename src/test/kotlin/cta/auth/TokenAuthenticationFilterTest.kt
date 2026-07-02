package cta.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.util.ReflectionTestUtils

class TokenAuthenticationFilterTest {
    private val authService =
        AuthService().also {
            ReflectionTestUtils.setField(it, "adminHeader", "X-Auth-Admin-Secret")
            ReflectionTestUtils.setField(it, "secret", "s3cret")
        }
    private val filter = TokenAuthenticationFilter(authService, ObjectMapper())

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
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
        assertTrue(authentication.authorities.any { it.authority == "write:organisations" })
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
