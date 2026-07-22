package cta.app.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cta.app.FeatureFlag
import cta.app.FeatureFlagRepository
import cta.app.Kit
import cta.app.KitStatus
import cta.app.KitSubStatus
import cta.app.KitType
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
 * Guard matrix for the blocking-flag sub-status control (#90): each blocking flag x
 * blocked vs open target status x shadow-vs-enforce, plus the shadow-safety rule that a
 * guard failure (e.g. the flag read throwing) must never fail the host mutation. The
 * blocked set is the four statuses kit-info disables client-side
 * (ALLOCATION_DELIVERY_ARRANGED, ALLOCATION_QC_COMPLETED, ALLOCATION_READY,
 * DISTRIBUTION_DELIVERED) — an explicit set, never an ordinal comparison, so the
 * recycling/repair exits stay open for flagged kits.
 */
class BlockingFlagGuardServiceTest {
    private val featureFlags = mock(FeatureFlagRepository::class.java)
    private val guard = BlockingFlagGuardService(featureFlags)

    // kotlin-logging names a file-level logger after the file class minus the Kt suffix.
    private val guardLogger = LoggerFactory.getLogger("cta.app.services.BlockingFlagGuardService") as Logger
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

    private fun kit(subStatus: KitSubStatus = KitSubStatus()): Kit =
        Kit(
            id = 42L,
            type = KitType.LAPTOP,
            status = KitStatus.PROCESSING_START,
            model = "Test",
            subStatus = subStatus,
        )

    private fun enforcement(on: Boolean) {
        `when`(featureFlags.findById("blocking-flag-enforcement"))
            .thenReturn(Optional.of(FeatureFlag(key = "blocking-flag-enforcement", enabled = on)))
    }

    private fun warnings(): List<ILoggingEvent> = appender.list.filter { it.level == Level.WARN }

    // --- enforce mode: blocked transitions ---

    @Test
    fun `enforce - every blocking flag blocks every guarded target status`() {
        enforcement(on = true)
        val flagged =
            mapOf(
                "wipeFailed" to KitSubStatus(wipeFailed = true),
                "installationOfOSFailed" to KitSubStatus(installationOfOSFailed = true),
                "needsFurtherInvestigation" to KitSubStatus(needsFurtherInvestigation = true),
                "needsSparePart" to KitSubStatus(needsSparePart = true),
                "lockedToUser" to KitSubStatus(lockedToUser = true),
            )
        flagged.forEach { (name, subStatus) ->
            BlockingFlagGuardService.BLOCKED_TARGET_STATUSES.forEach { target ->
                val ex =
                    assertThrows(BlockingFlagSetException::class.java, {
                        guard.checkStatusChange(kit(subStatus), KitStatus.PROCESSING_START, target, "updateKit")
                    }, "expected $name to block $target")
                assertTrue(ex.message!!.contains("42"), "message must name the kit id: ${ex.message}")
                assertTrue(ex.message!!.contains(name), "message must name the offending flag: ${ex.message}")
            }
        }
    }

    @Test
    fun `enforce - the message names every flag that is set`() {
        enforcement(on = true)
        val ex =
            assertThrows(BlockingFlagSetException::class.java) {
                guard.checkStatusChange(
                    kit(KitSubStatus(wipeFailed = true, needsSparePart = true)),
                    KitStatus.PROCESSING_START,
                    KitStatus.ALLOCATION_READY,
                    "updateKit",
                )
            }
        assertTrue(ex.message!!.contains("wipeFailed"), ex.message)
        assertTrue(ex.message!!.contains("needsSparePart"), ex.message)
    }

    @Test
    fun `enforce - a flagged kit cannot be assigned to a device request`() {
        enforcement(on = true)
        val ex =
            assertThrows(BlockingFlagSetException::class.java) {
                guard.checkAssignment(kit(KitSubStatus(lockedToUser = true)), "assignKitsToDeviceRequest")
            }
        assertTrue(ex.message!!.contains("42"))
        assertTrue(ex.message!!.contains("lockedToUser"), ex.message)
    }

    // --- enforce mode: allowed cases ---

    @Test
    fun `enforce - a kit with no blocking flags progresses and assigns freely`() {
        enforcement(on = true)
        BlockingFlagGuardService.BLOCKED_TARGET_STATUSES.forEach { target ->
            guard.checkStatusChange(kit(), KitStatus.PROCESSING_START, target, "updateKit")
        }
        guard.checkAssignment(kit(), "assignKitsToDeviceRequest")
    }

    @Test
    fun `enforce - null flags are not set flags`() {
        // KitSubStatus booleans are nullable; a null must read as "not flagged".
        enforcement(on = true)
        guard.checkStatusChange(
            kit(KitSubStatus(wipeFailed = null, lockedToUser = null)),
            KitStatus.PROCESSING_START,
            KitStatus.ALLOCATION_READY,
            "updateKit",
        )
    }

    @Test
    fun `enforce - non-blocked target statuses stay open for a flagged kit`() {
        enforcement(on = true)
        val flagged = KitSubStatus(wipeFailed = true)
        KitStatus.entries
            .filter { it !in BlockingFlagGuardService.BLOCKED_TARGET_STATUSES }
            .forEach { target ->
                guard.checkStatusChange(kit(flagged), KitStatus.PROCESSING_START, target, "updateKit")
            }
    }

    @Test
    fun `enforce - unchanged status is not a transition`() {
        // Full-replace updates always resend the current status; editing an
        // already-progressed flagged kit must keep working.
        enforcement(on = true)
        guard.checkStatusChange(
            kit(KitSubStatus(wipeFailed = true)),
            KitStatus.ALLOCATION_READY,
            KitStatus.ALLOCATION_READY,
            "updateKit",
        )
    }

    // --- shadow mode ---

    @Test
    fun `shadow - would-block is allowed and WARN-logged with kit flags transition and point`() {
        enforcement(on = false)
        guard.checkStatusChange(
            kit(KitSubStatus(wipeFailed = true, needsSparePart = true)),
            KitStatus.PROCESSING_START,
            KitStatus.ALLOCATION_READY,
            "updateKit",
        )

        val line = warnings().single().formattedMessage
        assertTrue(line.startsWith("blocking-flag would-block:"), line)
        assertTrue(line.contains("kitId=42"), line)
        assertTrue(line.contains("flags=wipeFailed,needsSparePart"), line)
        assertTrue(line.contains("transition=PROCESSING_START->ALLOCATION_READY"), line)
        assertTrue(line.contains("enforcementPoint=updateKit"), line)
        assertTrue(line.contains("(shadow mode: allowed)"), line)
    }

    @Test
    fun `shadow - assignment would-block is allowed and WARN-logged`() {
        enforcement(on = false)
        guard.checkAssignment(kit(KitSubStatus(lockedToUser = true)), "assignKitsToDeviceRequest")

        val line = warnings().single().formattedMessage
        assertTrue(line.contains("kitId=42"), line)
        assertTrue(line.contains("flags=lockedToUser"), line)
        assertTrue(line.contains("enforcementPoint=assignKitsToDeviceRequest"), line)
    }

    @Test
    fun `shadow - allowed cases log nothing`() {
        enforcement(on = false)
        guard.checkStatusChange(kit(), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
        guard.checkStatusChange(
            kit(KitSubStatus(wipeFailed = true)),
            KitStatus.PROCESSING_START,
            KitStatus.DISTRIBUTION_RECYCLED,
            "updateKit",
        )
        guard.checkAssignment(kit(), "assignKitsToDeviceRequest")
        assertEquals(0, warnings().size)
    }

    @Test
    fun `missing flag row means shadow mode`() {
        `when`(featureFlags.findById("blocking-flag-enforcement")).thenReturn(Optional.empty())
        guard.checkStatusChange(
            kit(KitSubStatus(wipeFailed = true)),
            KitStatus.PROCESSING_START,
            KitStatus.ALLOCATION_READY,
            "updateKit",
        )
        assertEquals(1, warnings().size)
    }

    @Test
    fun `flag read failure never fails the host mutation`() {
        // Telemetry-interceptor discipline: a guard-internal error (here the flag read,
        // the only I/O the guard performs) degrades to shadow — log and allow.
        `when`(featureFlags.findById("blocking-flag-enforcement")).thenThrow(RuntimeException("db down"))
        guard.checkStatusChange(
            kit(KitSubStatus(wipeFailed = true)),
            KitStatus.PROCESSING_START,
            KitStatus.ALLOCATION_READY,
            "updateKit",
        )
        guard.checkAssignment(kit(KitSubStatus(wipeFailed = true)), "assignKitsToDeviceRequest")
        assertTrue(warnings().isNotEmpty())
    }
}
