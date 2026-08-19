package cta.app

import cta.app.graphql.mutations.BoroughAvailabilityMutations
import cta.app.graphql.mutations.BoroughGroupInput
import cta.app.graphql.mutations.DeviceAvailabilityInput
import cta.app.graphql.mutations.SaveBoroughAvailabilityInput
import cta.app.graphql.queries.BoroughAvailabilityQueries
import cta.app.services.BoroughAvailabilityRules
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestPropertySource

/**
 * Database-backed coverage for the borough-availability config (#179): the Flyway seed, the
 * public read path, and the admin save path's validate-before-write / wholesale-replace
 * behaviour.
 *
 * The seed is exactly two rows and every test after the first mutates that same shared config,
 * so - unlike most DB-backed tests in this repo, which coexist via distinct ids on an otherwise
 * untouched table - these genuinely can't run in an arbitrary order against a shared table. A
 * distinct `spring.application.name` forks this class its own Spring context (and therefore its
 * own embedded Postgres, freshly migrated), following the isolation pattern already used by
 * DeliveryBookingProtectionTest/DeliveryBookingConcurrencyTest; `@TestMethodOrder` then makes the
 * intra-class sequence explicit instead of relying on an unspecified default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@TestPropertySource(properties = ["spring.application.name=techaid-server-borough-availability-test"])
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BoroughAvailabilityTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var boroughGroups: BoroughGroupRepository

    @Autowired
    lateinit var queries: BoroughAvailabilityQueries

    @Autowired
    lateinit var mutations: BoroughAvailabilityMutations

    @Autowired
    lateinit var featureFlags: FeatureFlagRepository

    /**
     * Flyway seeds borough-availability-rules OFF, so the public query serves nothing until it is
     * switched on. Every test here but the first is about what the configuration *does*, so they
     * all need it on. Safe to leave flipped between tests: this class already forks its own
     * context and embedded database.
     */
    @BeforeEach
    fun enableBoroughRules() {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = true))
    }

    @Test
    @Order(0)
    @WithMockUser(authorities = ["app:admin"])
    fun `with the flag off the public query is empty but admins still see everything`() {
        featureFlags.save(FeatureFlag(key = BoroughAvailabilityRules.FLAG_KEY, enabled = false))

        // The public form reads an empty list as "no restriction recorded" and offers every
        // device type, which is the pre-config behaviour this flag exists to preserve.
        assertThat(queries.boroughAvailabilityPublic()).isEmpty()

        // The whole point of gating only the public path: staff can build and review the matrix,
        // Tower Hamlets included, while the public journey is untouched.
        val groups = queries.boroughGroups().associateBy { it.name }
        assertThat(groups.keys).containsExactlyInAnyOrder("Lambeth & Southwark", "Tower Hamlets")
        assertThat(groups.getValue("Tower Hamlets").availability).isNotEmpty()
        assertThat(queries.referrerLimitExceptions()).isEmpty()
    }

    @Test
    @Order(1)
    fun `seed is exactly two groups matching todays boroughs, unchanged`() {
        val groups = boroughGroups.findAll().associateBy { it.name }
        assertThat(groups).hasSize(2)

        val lambethSouthwark = groups.getValue("Lambeth & Southwark")
        assertThat(lambethSouthwark.boroughs).containsExactlyInAnyOrder("Lambeth", "Southwark")
        assertThat(lambethSouthwark.status).isEqualTo(BoroughGroupStatus.LIVE)
        assertThat(lambethSouthwark.maxPerReferee).isEqualTo(3)
        assertThat(lambethSouthwark.offeredDeviceTypes()).containsExactlyElementsOf(DeviceType.keys)

        val towerHamlets = groups.getValue("Tower Hamlets")
        assertThat(towerHamlets.boroughs).containsExactly("Tower Hamlets")
        assertThat(towerHamlets.status).isEqualTo(BoroughGroupStatus.PILOT)
        assertThat(towerHamlets.maxPerReferee).isEqualTo(1)
        assertThat(towerHamlets.offeredDeviceTypes()).containsExactly(DeviceType.LAPTOPS.key)
    }

    @Test
    @Order(2)
    fun `boroughAvailabilityPublic is keyed by borough, not group`() {
        val rows = queries.boroughAvailabilityPublic().associateBy { it.borough }
        assertThat(rows.keys).containsExactlyInAnyOrder("Lambeth", "Southwark", "Tower Hamlets")

        val lambeth = rows.getValue("Lambeth")
        val southwark = rows.getValue("Southwark")
        assertThat(lambeth.offered).containsExactlyElementsOf(DeviceType.keys)
        assertThat(southwark.offered).isEqualTo(lambeth.offered)
        assertThat(lambeth.maxPerReferee).isEqualTo(3)
        assertThat(southwark.maxPerReferee).isEqualTo(3)

        val towerHamlets = rows.getValue("Tower Hamlets")
        assertThat(towerHamlets.offered).containsExactly(DeviceType.LAPTOPS.key)
        assertThat(towerHamlets.maxPerReferee).isEqualTo(1)
    }

    @Test
    @Order(3)
    @WithMockUser(authorities = ["app:admin"])
    fun `AUTO resolves to closed through the public query`() {
        val groups = boroughGroups.findAll()
        val lambethSouthwark = groups.first { it.name == "Lambeth & Southwark" }
        val towerHamlets = groups.first { it.name == "Tower Hamlets" }

        mutations.saveBoroughAvailability(
            SaveBoroughAvailabilityInput(
                groups =
                    listOf(
                        lambethSouthwark.toInput(),
                        towerHamlets.toInput(overrides = mapOf(DeviceType.TABLETS.key to "AUTO")),
                    ),
                exceptions = emptyList(),
            ),
        )

        val towerHamletsPublic = queries.boroughAvailabilityPublic().first { it.borough == "Tower Hamlets" }
        assertThat(towerHamletsPublic.unresolvedAuto).containsExactly(DeviceType.TABLETS.key)
        assertThat(towerHamletsPublic.offered)
            .doesNotContain(DeviceType.TABLETS.key)
            .containsExactly(DeviceType.LAPTOPS.key)
    }

    @Test
    @Order(4)
    @WithMockUser(authorities = ["app:admin"])
    fun `a borough cannot be in two groups`() {
        val input =
            SaveBoroughAvailabilityInput(
                groups =
                    listOf(
                        BoroughGroupInput(
                            name = "Group A",
                            boroughs = listOf("Lambeth"),
                            status = "LIVE",
                            maxPerReferee = 1,
                            availability = emptyList(),
                        ),
                        BoroughGroupInput(
                            name = "Group B",
                            boroughs = listOf("Lambeth"),
                            status = "LIVE",
                            maxPerReferee = 1,
                            availability = emptyList(),
                        ),
                    ),
                exceptions = emptyList(),
            )

        assertThatThrownBy { mutations.saveBoroughAvailability(input) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Lambeth")
    }

    @Test
    @Order(5)
    @WithMockUser(authorities = ["app:admin"])
    fun `validation rejects before writing anything`() {
        val before = boroughGroups.findAll()
        val beforeCount = before.size
        val towerHamletsBefore = before.first { it.name == "Tower Hamlets" }
        val towerHamletsOfferedBefore = towerHamletsBefore.offeredDeviceTypes()

        val badInput =
            SaveBoroughAvailabilityInput(
                groups =
                    listOf(
                        BoroughGroupInput(
                            id = towerHamletsBefore.id,
                            name = towerHamletsBefore.name,
                            boroughs = towerHamletsBefore.boroughs.toList(),
                            status = towerHamletsBefore.status.name,
                            maxPerReferee = towerHamletsBefore.maxPerReferee,
                            availability = listOf(DeviceAvailabilityInput(deviceType = "flying-cars", mode = "ON")),
                        ),
                    ),
                exceptions = emptyList(),
            )

        // This input also omits the Lambeth & Southwark group entirely - if validation ran after
        // any writes, that group would be deleted as "absent from the input". It must not be.
        assertThatThrownBy { mutations.saveBoroughAvailability(badInput) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(boroughGroups.findAll()).hasSize(beforeCount)
        assertThat(boroughGroups.findAll().any { it.name == "Lambeth & Southwark" }).isTrue()
        assertThat(boroughGroups.findAll().first { it.name == "Tower Hamlets" }.offeredDeviceTypes())
            .isEqualTo(towerHamletsOfferedBefore)
    }

    @Test
    @Order(6)
    @WithMockUser(authorities = ["app:admin"])
    fun `omitted device types default to OFF, not to whatever was stored before`() {
        val lambethSouthwark = boroughGroups.findAll().first { it.name == "Lambeth & Southwark" }
        val towerHamlets = boroughGroups.findAll().first { it.name == "Tower Hamlets" }

        mutations.saveBoroughAvailability(
            SaveBoroughAvailabilityInput(
                groups =
                    listOf(
                        BoroughGroupInput(
                            id = lambethSouthwark.id,
                            name = lambethSouthwark.name,
                            boroughs = lambethSouthwark.boroughs.toList(),
                            status = lambethSouthwark.status.name,
                            maxPerReferee = lambethSouthwark.maxPerReferee,
                            // Every other device type was ON going into this save; omitting them
                            // here must turn them OFF rather than leave them as they were.
                            availability = listOf(DeviceAvailabilityInput(deviceType = DeviceType.LAPTOPS.key, mode = "ON")),
                        ),
                        towerHamlets.toInput(),
                    ),
                exceptions = emptyList(),
            ),
        )

        val updated = boroughGroups.findAll().first { it.name == "Lambeth & Southwark" }
        assertThat(updated.offeredDeviceTypes()).containsExactly(DeviceType.LAPTOPS.key)
        assertThat(updated.availability.map { it.deviceType }).containsExactlyInAnyOrderElementsOf(DeviceType.keys)
        assertThat(updated.availability.filter { it.deviceType != DeviceType.LAPTOPS.key })
            .allMatch { it.mode == AvailabilityMode.OFF }
    }

    @Test
    @Order(7)
    @WithMockUser(authorities = ["app:admin"])
    fun `wholesale replace deletes groups absent from the input`() {
        val lambethSouthwark = boroughGroups.findAll().first { it.name == "Lambeth & Southwark" }

        mutations.saveBoroughAvailability(
            SaveBoroughAvailabilityInput(
                groups = listOf(lambethSouthwark.toInput()),
                exceptions = emptyList(),
            ),
        )

        val remaining = boroughGroups.findAll()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.none { it.name == "Tower Hamlets" }).isTrue()
    }

    /** Converts a stored group to a save input identical to its current state, bar [overrides]. */
    private fun BoroughGroup.toInput(overrides: Map<String, String> = emptyMap()): BoroughGroupInput =
        BoroughGroupInput(
            id = id,
            name = name,
            boroughs = boroughs.toList(),
            status = status.name,
            maxPerReferee = maxPerReferee,
            availability =
                DeviceType.keys.map { key ->
                    val current = availability.firstOrNull { it.deviceType == key }?.mode?.name ?: "OFF"
                    DeviceAvailabilityInput(deviceType = key, mode = overrides[key] ?: current)
                },
        )
}
