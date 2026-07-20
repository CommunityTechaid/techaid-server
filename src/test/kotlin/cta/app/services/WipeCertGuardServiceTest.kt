package cta.app.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import cta.app.Kit
import cta.app.KitStatus
import cta.app.KitType
import cta.app.WipeCertExemption
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.util.Optional

/**
 * Guard matrix for the wipe-cert status-progression control (#68): type scoping
 * (drive-bearing only) x blocked vs exempt target status x cert/exemption/neither x
 * shadow-vs-enforce, plus the shadow-safety rule that a guard failure (e.g. the flag
 * read throwing) must never fail the host mutation. The exit statuses
 * DISTRIBUTION_RECYCLED / DISTRIBUTION_REPAIR_RETURN stay open without a cert — the
 * control protects client-bound devices, not recycling/returns.
 */
class WipeCertGuardServiceTest {
    private val featureFlags = mock(FeatureFlagRepository::class.java)
    private val guard = WipeCertGuardService(featureFlags)

    // kotlin-logging names a file-level logger after the file class minus the Kt suffix.
    private val guardLogger = LoggerFactory.getLogger("cta.app.services.WipeCertGuardService") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        guardLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        guardLogger.detachAppender(appender)
        appender.stop()
    }

    private fun kit(
        type: KitType = KitType.LAPTOP,
        cert: String? = null,
        exemption: WipeCertExemption? = null,
    ): Kit =
        Kit(
            id = 42L,
            type = type,
            status = KitStatus.PROCESSING_WIPED,
            model = "Test",
            wipeCertReference = cert,
            wipeCertExemption = exemption,
        )

    private fun enforcement(on: Boolean) {
        `when`(featureFlags.findById("wipe-cert-enforcement"))
            .thenReturn(Optional.of(FeatureFlag(key = "wipe-cert-enforcement", enabled = on)))
    }

    private fun warnings(): List<ILoggingEvent> = appender.list.filter { it.level == Level.WARN }

    // --- enforce mode: blocked transitions ---

    @Test
    fun `enforce - certless laptop is blocked from every guarded target status`() {
        enforcement(on = true)
        WipeCertGuardService.BLOCKED_TARGET_STATUSES.forEach { target ->
            val ex =
                assertThrows(WipeCertMissingException::class.java, {
                    guard.checkStatusChange(kit(), KitStatus.PROCESSING_WIPED, target, "updateKit")
                }, "expected $target to be blocked")
            assertTrue(ex.message!!.contains("42"), "message must name the kit id: ${ex.message}")
            assertTrue(ex.message!!.contains("wipe certificate"), "message must name the missing cert: ${ex.message}")
        }
    }

    @Test
    fun `enforce - blank cert reference counts as missing`() {
        enforcement(on = true)
        assertThrows(WipeCertMissingException::class.java) {
            guard.checkStatusChange(kit(cert = " "), KitStatus.PROCESSING_WIPED, KitStatus.ALLOCATION_READY, "updateKit")
        }
    }

    @Test
    fun `enforce - certless laptop cannot be assigned to a device request`() {
        enforcement(on = true)
        val ex =
            assertThrows(WipeCertMissingException::class.java) {
                guard.checkAssignment(kit(), "assignKitsToDeviceRequest")
            }
        assertTrue(ex.message!!.contains("42"))
    }

    // --- enforce mode: allowed cases ---

    @Test
    fun `enforce - recorded cert allows progression and assignment`() {
        enforcement(on = true)
        guard.checkStatusChange(kit(cert = "2026-07-19--42--SN123.json"), KitStatus.PROCESSING_WIPED, KitStatus.DISTRIBUTION_DELIVERED, "updateKit")
        guard.checkAssignment(kit(cert = "2026-07-19--42--SN123.json"), "assignKitsToDeviceRequest")
    }

    @Test
    fun `enforce - admin exemption allows progression and assignment`() {
        enforcement(on = true)
        guard.checkStatusChange(kit(exemption = WipeCertExemption.NO_DRIVE), KitStatus.PROCESSING_WIPED, KitStatus.ALLOCATION_READY, "updateKit")
        guard.checkAssignment(kit(exemption = WipeCertExemption.NO_DRIVE), "assignKitsToDeviceRequest")
    }

    @Test
    fun `enforce - non drive-bearing types are out of scope`() {
        enforcement(on = true)
        listOf(KitType.SMARTPHONE, KitType.TABLET, KitType.COMMSDEVICE, KitType.BROADBANDHUB, KitType.OTHER).forEach { type ->
            guard.checkStatusChange(kit(type = type), KitStatus.PROCESSING_WIPED, KitStatus.DISTRIBUTION_DELIVERED, "updateKit")
            guard.checkAssignment(kit(type = type), "assignKitsToDeviceRequest")
        }
    }

    @Test
    fun `enforce - exit statuses recycled and repair-return stay open without a cert`() {
        enforcement(on = true)
        listOf(KitStatus.DISTRIBUTION_RECYCLED, KitStatus.DISTRIBUTION_REPAIR_RETURN).forEach { target ->
            guard.checkStatusChange(kit(), KitStatus.PROCESSING_WIPED, target, "updateKit")
        }
    }

    @Test
    fun `enforce - moving backward or within the pre-wipe stages needs no cert`() {
        enforcement(on = true)
        listOf(KitStatus.DONATION_NEW, KitStatus.PROCESSING_START, KitStatus.PROCESSING_WIPED).forEach { target ->
            guard.checkStatusChange(kit(), KitStatus.ALLOCATION_READY, target, "updateKit")
        }
    }

    @Test
    fun `enforce - unchanged status is not a transition`() {
        // Full-replace updates always resend the current status; editing an
        // already-progressed certless kit must keep working.
        enforcement(on = true)
        guard.checkStatusChange(kit(), KitStatus.ALLOCATION_READY, KitStatus.ALLOCATION_READY, "updateKit")
    }

    // --- shadow mode ---

    @Test
    fun `shadow - would-block is allowed and WARN-logged with kit transition and point`() {
        enforcement(on = false)
        guard.checkStatusChange(kit(), KitStatus.PROCESSING_WIPED, KitStatus.ALLOCATION_READY, "updateKit")

        val warn = warnings().single()
        val line = warn.formattedMessage
        assertTrue(line.contains("kitId=42"), line)
        assertTrue(line.contains("PROCESSING_WIPED"), line)
        assertTrue(line.contains("ALLOCATION_READY"), line)
        assertTrue(line.contains("enforcementPoint=updateKit"), line)
    }

    @Test
    fun `shadow - assignment would-block is allowed and WARN-logged`() {
        enforcement(on = false)
        guard.checkAssignment(kit(), "assignKitsToDeviceRequest")

        val line = warnings().single().formattedMessage
        assertTrue(line.contains("kitId=42"), line)
        assertTrue(line.contains("enforcementPoint=assignKitsToDeviceRequest"), line)
    }

    @Test
    fun `shadow - allowed cases log nothing`() {
        enforcement(on = false)
        guard.checkStatusChange(kit(cert = "ref"), KitStatus.PROCESSING_WIPED, KitStatus.ALLOCATION_READY, "updateKit")
        guard.checkStatusChange(kit(type = KitType.SMARTPHONE), KitStatus.PROCESSING_WIPED, KitStatus.ALLOCATION_READY, "updateKit")
        assertEquals(0, warnings().size)
    }

    @Test
    fun `missing flag row means shadow mode`() {
        `when`(featureFlags.findById("wipe-cert-enforcement")).thenReturn(Optional.empty())
        guard.checkStatusChange(kit(), KitStatus.PROCESSING_WIPED, KitStatus.ALLOCATION_READY, "updateKit")
        assertEquals(1, warnings().size)
    }

    @Test
    fun `flag read failure never fails the host mutation`() {
        // Telemetry-interceptor discipline: a guard-internal error (here the flag read,
        // the only I/O the guard performs) degrades to shadow — log and allow.
        `when`(featureFlags.findById("wipe-cert-enforcement")).thenThrow(RuntimeException("db down"))
        guard.checkStatusChange(kit(), KitStatus.PROCESSING_WIPED, KitStatus.ALLOCATION_READY, "updateKit")
        guard.checkAssignment(kit(), "assignKitsToDeviceRequest")
        assertTrue(warnings().isNotEmpty())
    }
}
