package cta.app.graphql.mutations

import cta.app.Donor
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser

/**
 * `deleteDonor` detaches the donor's kits before deleting the donor, so that the devices survive
 * with their donation provenance cleared rather than being deleted or orphaned by a foreign key.
 *
 * It did that with:
 *
 *     donor.kits.forEach { donor.removeKit(it) }
 *
 * `removeKit` runs `kits.removeIf { ... }` on the very set `forEach` is walking, which throws
 * `ConcurrentModificationException`.
 *
 * IT TAKES TWO KITS. `Donor.kits` is a `MutableSet`, and a HashMap iterator only compares modCount
 * inside `next()` — never in `hasNext()`. Removing the sole element is therefore never noticed:
 * the loop simply ends. With two or more, the second `next()` throws. That is why this survived in
 * production and why the one-kit case below passes with or without the fix; it is kept as the
 * boundary, not as the reproduction.
 *
 * The second test is the reproduction and fails before the fix. It and the third also pin what the
 * loop was there to do, so a future "simplification" that drops the detach entirely — letting the
 * FK's ON DELETE SET NULL do it silently, or worse, cascading — fails here too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class DonorDeletionTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var donors: DonorRepository

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var donorMutations: DonorMutations

    @Test
    @WithMockUser(authorities = ["delete:donors"])
    fun `deleting a donor holding a single kit works - the boundary, not the bug`() {
        val donor = newDonor("one-kit@example.com")
        kits.save(Kit(model = "Only kit", age = 1, donor = donor))

        assertThat(donorMutations.deleteDonor(donor.id))
            .`as`("a lone element is removed without the iterator ever checking modCount")
            .isTrue()

        assertThat(donors.findById(donor.id)).isEmpty
    }

    @Test
    @WithMockUser(authorities = ["delete:donors"])
    fun `deleting a donor holding several kits does not blow up mid-iteration`() {
        val donor = newDonor("several-kits@example.com")
        val kitIds = (1..4).map { kits.save(Kit(model = "Kit $it", age = 1, donor = donor)).id }

        assertThat(donorMutations.deleteDonor(donor.id))
            .`as`("ConcurrentModificationException before the fix - the second next() sees the modCount move")
            .isTrue()

        val survivors = kitIds.map { kits.findById(it).orElseThrow() }
        assertThat(survivors)
            .`as`("deleting a donor must not delete the devices they gave us")
            .hasSize(4)
        assertThat(survivors.map { it.donor })
            .`as`("but the donation provenance must be cleared")
            .containsOnlyNulls()
    }

    @Test
    @WithMockUser(authorities = ["delete:donors"])
    fun `a donor with no kits still deletes`() {
        val donor = newDonor("no-kits@example.com")

        assertThat(donorMutations.deleteDonor(donor.id)).isTrue()
        assertThat(donors.findById(donor.id)).isEmpty
    }

    private fun newDonor(email: String) =
        donors.save(
            Donor(
                name = "Deletable Donor",
                email = email,
                phoneNumber = "07000000000",
                postCode = "SE1 1AA",
                referral = "test",
            ),
        )
}
