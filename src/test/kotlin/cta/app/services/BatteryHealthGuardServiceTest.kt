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
 * Guard matrix for the battery-health control (#264): a battery-bearing kit (laptop, tablet,
 * smartphone) at 50% or below, or with no reading, can't move into ALLOCATION_READY,
 * ALLOCATION_QC_COMPLETED or ALLOCATION_DELIVERY_ARRANGED. The DISTRIBUTION_* exits stay
 * open. Shadow mode (the seeded default) only WARN-logs.
 */
class BatteryHealthGuardServiceTest {
    private val featureFlags = mock(FeatureFlagRepository::class.java)
    private val guard = BatteryHealthGuardService(featureFlags)

    private val guardLogger = LoggerFactory.getLogger("cta.app.services.BatteryHealthGuardService") as Logger
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
        batteryHealth: Int?,
        type: KitType = KitType.LAPTOP,
    ): Kit =
        Kit(
            id = 42L,
            type = type,
            status = KitStatus.PROCESSING_START,
            model = "Test",
            batteryHealth = batteryHealth,
        )

    private fun enforcement(on: Boolean) {
        `when`(featureFlags.findById("battery-health-enforcement"))
            .thenReturn(Optional.of(FeatureFlag(key = "battery-health-enforcement", enabled = on)))
    }

    private fun warnings(): List<ILoggingEvent> = appender.list.filter { it.level == Level.WARN }

    @Test
    fun `the blocked set is exactly the three allocation statuses`() {
        assertEquals(
            setOf(KitStatus.ALLOCATION_READY, KitStatus.ALLOCATION_QC_COMPLETED, KitStatus.ALLOCATION_DELIVERY_ARRANGED),
            BatteryHealthGuardService.BLOCKED_TARGET_STATUSES,
        )
    }

    @Test
    fun `enforce - 50 percent or below blocks every guarded status on every battery type`() {
        enforcement(on = true)
        listOf(KitType.LAPTOP, KitType.TABLET, KitType.SMARTPHONE).forEach { type ->
            listOf(0, 49, 50).forEach { health ->
                BatteryHealthGuardService.BLOCKED_TARGET_STATUSES.forEach { target ->
                    val ex =
                        assertThrows(BatteryHealthTooLowException::class.java, {
                            guard.checkStatusChange(kit(health, type), KitStatus.PROCESSING_START, target, "updateKit")
                        }, "expected $type at $health% to block $target")
                    assertTrue(ex.message!!.contains("Kit 42"), ex.message)
                    assertTrue(ex.message!!.contains("Battery health too low"), ex.message)
                }
            }
        }
    }

    @Test
    fun `enforce - a missing reading on a battery type is blocked`() {
        enforcement(on = true)
        assertThrows(BatteryHealthTooLowException::class.java) {
            guard.checkStatusChange(kit(null), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
        }
    }

    @Test
    fun `enforce - above 50 percent progresses`() {
        enforcement(on = true)
        guard.checkStatusChange(kit(51), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
    }

    @Test
    fun `enforce - types without a battery are never checked`() {
        enforcement(on = true)
        listOf(KitType.DESKTOP, KitType.ALLINONE, KitType.OTHER, KitType.COMMSDEVICE, KitType.BROADBANDHUB).forEach {
            guard.checkStatusChange(kit(null, it), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
            guard.checkStatusChange(kit(10, it), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
        }
    }

    @Test
    fun `enforce - distribution exits stay open`() {
        enforcement(on = true)
        listOf(
            KitStatus.DISTRIBUTION_DELIVERED,
            KitStatus.DISTRIBUTION_RECYCLED,
            KitStatus.DISTRIBUTION_REPAIR_RETURN,
        ).forEach {
            guard.checkStatusChange(kit(10), KitStatus.ALLOCATION_READY, it, "updateKit")
        }
    }

    @Test
    fun `enforce - unchanged status is not a transition`() {
        enforcement(on = true)
        guard.checkStatusChange(kit(10), KitStatus.ALLOCATION_READY, KitStatus.ALLOCATION_READY, "updateKit")
    }

    @Test
    fun `shadow - would-block is allowed and WARN-logged with kit, reading, transition and point`() {
        enforcement(on = false)
        guard.checkStatusChange(kit(45), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKits")

        val line = warnings().single().formattedMessage
        assertTrue(line.startsWith("battery-health would-block:"), line)
        assertTrue(line.contains("kitId=42"), line)
        assertTrue(line.contains("batteryHealth=45"), line)
        assertTrue(line.contains("type=LAPTOP"), line)
        assertTrue(line.contains("transition=PROCESSING_START->ALLOCATION_READY"), line)
        assertTrue(line.contains("enforcementPoint=updateKits"), line)
    }

    @Test
    fun `shadow - a missing reading logs batteryHealth=null so it can be counted separately`() {
        enforcement(on = false)
        guard.checkStatusChange(kit(null), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
        assertTrue(warnings().single().formattedMessage.contains("batteryHealth=null"))
    }

    @Test
    fun `shadow - allowed cases log nothing`() {
        enforcement(on = false)
        guard.checkStatusChange(kit(80), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
        guard.checkStatusChange(kit(10, KitType.DESKTOP), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
        guard.checkStatusChange(kit(10), KitStatus.PROCESSING_START, KitStatus.DISTRIBUTION_DELIVERED, "updateKit")
        assertEquals(0, warnings().size)
    }

    @Test
    fun `missing flag row means shadow mode`() {
        `when`(featureFlags.findById("battery-health-enforcement")).thenReturn(Optional.empty())
        guard.checkStatusChange(kit(10), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
        assertEquals(1, warnings().size)
    }

    @Test
    fun `flag read failure never fails the host mutation`() {
        `when`(featureFlags.findById("battery-health-enforcement")).thenThrow(RuntimeException("db down"))
        guard.checkStatusChange(kit(10), KitStatus.PROCESSING_START, KitStatus.ALLOCATION_READY, "updateKit")
    }
}
