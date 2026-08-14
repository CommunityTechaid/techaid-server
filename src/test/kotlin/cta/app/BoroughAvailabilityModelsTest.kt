package cta.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Plain unit tests for the borough-availability model logic (#179) - no Spring context, no
 * database. Mirrors LenientStringScalarTest's style: fast, isolated checks of pure functions.
 */
class BoroughAvailabilityModelsTest {
    @Test
    fun `ON is offered, OFF and AUTO are not`() {
        assertThat(AvailabilityMode.ON.offered).isTrue()
        assertThat(AvailabilityMode.OFF.offered).isFalse()
        assertThat(AvailabilityMode.AUTO.offered)
            .`as`(
                "AUTO must resolve to closed - nothing computes the stock signal the mode implies, " +
                    "see the AvailabilityMode kdoc",
            ).isFalse()
    }

    @Test
    fun `offeredDeviceTypes returns only ON types in canonical DeviceType order, not insertion order`() {
        val group = BoroughGroup(name = "Test")
        group.availability =
            mutableSetOf(
                BoroughAvailability(group = group, deviceType = DeviceType.OTHER.key, mode = AvailabilityMode.ON),
                BoroughAvailability(group = group, deviceType = DeviceType.PHONES.key, mode = AvailabilityMode.ON),
                BoroughAvailability(group = group, deviceType = DeviceType.LAPTOPS.key, mode = AvailabilityMode.OFF),
                BoroughAvailability(group = group, deviceType = DeviceType.TABLETS.key, mode = AvailabilityMode.AUTO),
            )

        // Insertion order above is OTHER, PHONES; canonical DeviceType order puts PHONES first.
        assertThat(group.offeredDeviceTypes()).containsExactly(DeviceType.PHONES.key, DeviceType.OTHER.key)
    }

    @Test
    fun `unresolvedAutoDeviceTypes returns exactly the AUTO ones, in canonical order`() {
        val group = BoroughGroup(name = "Test")
        group.availability =
            mutableSetOf(
                BoroughAvailability(group = group, deviceType = DeviceType.DESKTOPS.key, mode = AvailabilityMode.AUTO),
                BoroughAvailability(group = group, deviceType = DeviceType.LAPTOPS.key, mode = AvailabilityMode.ON),
                BoroughAvailability(group = group, deviceType = DeviceType.TABLETS.key, mode = AvailabilityMode.AUTO),
                BoroughAvailability(group = group, deviceType = DeviceType.PHONES.key, mode = AvailabilityMode.OFF),
            )

        assertThat(group.unresolvedAutoDeviceTypes())
            .containsExactly(DeviceType.TABLETS.key, DeviceType.DESKTOPS.key)
    }

    @Test
    fun `byKey resolves a known key and returns null for an unknown one`() {
        assertThat(DeviceType.byKey("allInOnes")).isEqualTo(DeviceType.ALL_IN_ONES)
        assertThat(DeviceType.byKey("nonsense")).isNull()
    }

    @Test
    fun `keys contains all eight expected camelCase spellings`() {
        assertThat(DeviceType.keys)
            .containsExactly(
                "phones",
                "tablets",
                "laptops",
                "allInOnes",
                "desktops",
                "commsDevices",
                "broadbandHubs",
                "other",
            )
    }
}
