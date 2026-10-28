package cta.app.graphql.mutations

import cta.app.AdminConfig
import cta.app.MembershipOverLimitMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * updateAdminConfig is written by two dashboard screens that each own part of the row: the
 * borough-availability matrix (the six canPublicRequest* switches) and the membership settings
 * (over-limit mode and grace count). Each sends only its own fields, so a missing field must mean
 * "unchanged" — otherwise saving one screen resets the other's settings.
 */
class UpdateAdminConfigInputTest {
    private fun existing() =
        AdminConfig(
            canPublicRequestSIMCard = false,
            canPublicRequestLaptop = true,
            canPublicRequestPhone = false,
            canPublicRequestBroadbandHub = true,
            canPublicRequestTablet = false,
            canPublicRequestDesktop = true,
        ).apply {
            membershipOverLimitMode = MembershipOverLimitMode.GRACE
            membershipGraceRequests = 2
        }

    @Test
    fun `membership-only input leaves the device switches unchanged`() {
        val updated =
            UpdateAdminConfigInput(
                membershipOverLimitMode = MembershipOverLimitMode.BLOCK,
                membershipGraceRequests = 0,
            ).apply(existing())

        assertEquals(MembershipOverLimitMode.BLOCK, updated.membershipOverLimitMode)
        assertEquals(0, updated.membershipGraceRequests)
        assertFalse(updated.canPublicRequestSIMCard)
        assertEquals(true, updated.canPublicRequestLaptop)
        assertFalse(updated.canPublicRequestPhone)
        assertEquals(true, updated.canPublicRequestBroadbandHub)
        assertFalse(updated.canPublicRequestTablet)
        assertEquals(true, updated.canPublicRequestDesktop)
    }

    @Test
    fun `device-switch-only input leaves the membership settings unchanged`() {
        val updated =
            UpdateAdminConfigInput(
                canPublicRequestSIMCard = true,
                canPublicRequestLaptop = false,
                canPublicRequestPhone = true,
                canPublicRequestBroadbandHub = false,
                canPublicRequestTablet = true,
                canPublicRequestDesktop = false,
            ).apply(existing())

        assertEquals(true, updated.canPublicRequestSIMCard)
        assertFalse(updated.canPublicRequestLaptop)
        assertEquals(MembershipOverLimitMode.GRACE, updated.membershipOverLimitMode)
        assertEquals(2, updated.membershipGraceRequests)
    }
}
