package cta.app.graphql.mutations

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * createReferringOrganisationContact is part of the PUBLIC (unauthenticated) surface, so whatever
 * the request form posts is what gets stored. A contact email carrying whitespace in the local part
 * was accepted verbatim and later made jakarta.mail's InternetAddress throw inside the stale-intake
 * sweeper, stalling the whole sweep (see DeviceRequestServiceTest). Bean validation is the boundary
 * that has to reject it.
 */
class ReferringOrganisationContactInputValidationTest {
    private fun createInput(email: String) =
        CreateReferringOrganisationContactInput(
            fullName = "Referee",
            address = "1 Test Street",
            email = email,
            phoneNumber = "02034887742",
            referringOrganisation = 1L,
        )

    private fun emailViolations(email: String) = validator.validate(createInput(email)).filter { it.propertyPath.toString() == "email" }

    @Test
    fun `accepts a well-formed email`() {
        assertTrue(emailViolations("referee@example.org").isEmpty())
    }

    @Test
    fun `rejects whitespace in the local part`() {
        assertEquals(1, emailViolations("bad address@example.org").size)
    }

    @Test
    fun `rejects surrounding whitespace`() {
        assertEquals(1, emailViolations(" referee@example.org ").size)
    }

    @Test
    fun `rejects an address with no domain`() {
        assertEquals(1, emailViolations("referee").size)
    }

    @Test
    fun `still rejects a blank email`() {
        assertTrue(emailViolations("").isNotEmpty(), "NotBlank must survive alongside Email")
    }

    companion object {
        private val factory = Validation.buildDefaultValidatorFactory()
        private val validator: Validator = factory.validator

        @JvmStatic
        @AfterAll
        fun tearDown() = factory.close()
    }
}
