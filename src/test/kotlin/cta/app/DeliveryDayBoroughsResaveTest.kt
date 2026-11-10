package cta.app

import cta.app.graphql.mutations.DeliveryAdminMutations
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * Issue #360: setDeliveryDayBoroughs deletes then re-inserts, and Hibernate flushes inserts
 * before deletes, so a new set overlapping the stored one violated
 * ux_delivery_day_boroughs_day_borough. Needs a real database; mocks cannot see the flush order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@TestPropertySource(properties = ["spring.application.name=techaid-server-day-boroughs-resave-test"])
class DeliveryDayBoroughsResaveTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mutations: DeliveryAdminMutations

    @Autowired
    lateinit var dayBoroughs: DeliveryDayBoroughRepository

    private fun stored(day: Int) = dayBoroughs.findAllByDayOfWeek(day).map { it.borough }.sorted()

    @Test
    @WithMockUser(authorities = ["write:organisations"])
    fun `re-saving an identical set keeps it`() {
        mutations.setDeliveryDayBoroughs(2, listOf("Hackney", "Camden"))
        mutations.setDeliveryDayBoroughs(2, listOf("Hackney", "Camden"))

        assertThat(stored(2)).containsExactly("Camden", "Hackney")
    }

    @Test
    @WithMockUser(authorities = ["write:organisations"])
    fun `saving an overlapping set replaces the stored one`() {
        mutations.setDeliveryDayBoroughs(3, listOf("Hackney", "Camden"))
        mutations.setDeliveryDayBoroughs(3, listOf("Hackney", "Islington"))

        assertThat(stored(3)).containsExactly("Hackney", "Islington")
    }

    @Test
    @WithMockUser(authorities = ["write:organisations"])
    fun `adding a borough to an existing list keeps the others`() {
        mutations.setDeliveryDayBoroughs(4, listOf("Hackney"))
        mutations.setDeliveryDayBoroughs(4, listOf("Hackney", "Camden"))

        assertThat(stored(4)).containsExactly("Camden", "Hackney")
    }
}
