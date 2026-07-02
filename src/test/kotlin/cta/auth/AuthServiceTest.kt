package cta.auth

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils

class AuthServiceTest {
    private fun service(secret: String): AuthService {
        val service = AuthService()
        ReflectionTestUtils.setField(service, "secret", secret)
        return service
    }

    @Test
    fun `matching secret grants the admin user with full authorities`() {
        val admin = service("s3cret").adminForToken("s3cret")
        assertTrue(admin != null, "matching secret must authenticate")
        assertTrue(admin!!.authorities.any { it.authority == "write:organisations" })
        assertTrue(admin.authorities.any { it.authority == "delete:users" })
    }

    @Test
    fun `non-matching token is rejected`() {
        assertNull(service("s3cret").adminForToken("wrong"))
    }

    @Test
    fun `token of a different length is rejected`() {
        assertNull(service("s3cret").adminForToken("s3cret-but-longer"))
    }

    @Test
    fun `blank token is rejected`() {
        assertNull(service("s3cret").adminForToken(""))
    }

    @Test
    fun `blank configured secret rejects everything`() {
        assertNull(service("").adminForToken(""))
    }
}
