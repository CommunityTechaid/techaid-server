package cta.app.graphql.filters

import cta.app.Donor
import cta.app.DonorParent
import cta.app.DonorParentRepository
import cta.app.DonorRepository
import cta.app.Kit
import cta.app.KitRepository
import cta.app.KitStatus
import cta.app.KitSubStatus
import cta.app.KitType
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.graphql.BooleanComparison
import cta.graphql.TextComparison
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * The OR-grouping fix from #168, applied to the remaining five where-input classes (#169).
 *
 * `BooleanBuilder.or()` disjoins against everything already on the builder, so
 * `{name: X, OR: [Y]}` built `X OR Y` instead of `X AND (Y)`: the OR swallowed its sibling filters.
 * As DeviceRequestOrGroupingTest explains, the predicate *looks* right, so these tests run it
 * through the repository and assert on the rows actually returned.
 *
 * Each test uses three fixtures: one matching both clauses (the only row the fixed semantics
 * returns), one matching only the sibling field, and one matching only the OR. Assertions are made
 * on the intersection with the fixtures' own ids, so rows other test classes leave in the shared
 * database can't affect them. The `empty OR` controls pass either way, by design.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class OrGroupingTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired lateinit var donors: DonorRepository

    @Autowired lateinit var donorParents: DonorParentRepository

    @Autowired lateinit var kits: KitRepository

    @Autowired lateinit var orgs: ReferringOrganisationRepository

    @Autowired lateinit var contacts: ReferringOrganisationContactRepository

    private fun contains(s: String) = TextComparison(_contains = s)

    private fun <T> visible(
        all: Iterable<T>,
        fixtures: Collection<T>,
    ): Set<T> = all.filter { it in fixtures }.toSet()

    @Test
    fun `DonorWhereInput - a field beside OR narrows instead of being swallowed`() {
        val both = donors.save(Donor(postCode = "", phoneNumber = "", email = "or169-hit@x.org", name = "OR169D-both", referral = ""))
        val nameOnly = donors.save(Donor(postCode = "", phoneNumber = "", email = "other@x.org", name = "OR169D-name", referral = ""))
        val orOnly = donors.save(Donor(postCode = "", phoneNumber = "", email = "or169-hit@x.org", name = "Elsewhere D", referral = ""))
        val fixtures = listOf(both, nameOnly, orOnly).map { it.id }

        val where = DonorWhereInput(name = contains("OR169D-"), OR = mutableListOf(DonorWhereInput(email = contains("or169-hit"))))
        assertThat(visible(donors.findAll(where.build()).map { it.id }, fixtures)).containsExactly(both.id)

        val control = DonorWhereInput(name = contains("OR169D-"))
        assertThat(visible(donors.findAll(control.build()).map { it.id }, fixtures)).containsExactlyInAnyOrder(both.id, nameOnly.id)
    }

    @Test
    fun `DonorParentWhereInput - a field beside OR narrows instead of being swallowed`() {
        val both = donorParents.save(DonorParent(name = "OR169P-both", address = "", website = "or169-hit.org"))
        val nameOnly = donorParents.save(DonorParent(name = "OR169P-name", address = "", website = "other.org"))
        val orOnly = donorParents.save(DonorParent(name = "Elsewhere P", address = "", website = "or169-hit.org"))
        val fixtures = listOf(both, nameOnly, orOnly).map { it.id }

        val where =
            DonorParentWhereInput(name = contains("OR169P-"), OR = mutableListOf(DonorParentWhereInput(website = contains("or169-hit"))))
        assertThat(visible(donorParents.findAll(where.build()).map { it.id }, fixtures)).containsExactly(both.id)
    }

    @Test
    fun `ReferringOrganisationWhereInput - a field beside OR narrows instead of being swallowed`() {
        val both = orgs.save(ReferringOrganisation(name = "OR169O-both", website = "or169-hit.org"))
        val nameOnly = orgs.save(ReferringOrganisation(name = "OR169O-name", website = "other.org"))
        val orOnly = orgs.save(ReferringOrganisation(name = "Elsewhere O", website = "or169-hit.org"))
        val fixtures = listOf(both, nameOnly, orOnly).map { it.id }

        val where =
            ReferringOrganisationWhereInput(
                name = contains("OR169O-"),
                OR = mutableListOf(ReferringOrganisationWhereInput(website = contains("or169-hit"))),
            )
        assertThat(visible(orgs.findAll(where.build()).map { it.id }, fixtures)).containsExactly(both.id)
    }

    @Test
    fun `ReferringOrganisationContactWhereInput - a field beside OR narrows instead of being swallowed`() {
        val org = orgs.save(ReferringOrganisation(name = "OR169 contact org"))

        fun contact(
            fullName: String,
            email: String,
        ) = contacts.save(
            ReferringOrganisationContact(
                fullName = fullName,
                email = email,
                phoneNumber = "07000000000",
                address = "1 Road",
                referringOrganisation = org,
            ),
        )
        val both = contact("OR169C-both", "or169-hit@x.org")
        val nameOnly = contact("OR169C-name", "other@x.org")
        val orOnly = contact("Elsewhere C", "or169-hit@x.org")
        val fixtures = listOf(both, nameOnly, orOnly).map { it.id }

        val where =
            ReferringOrganisationContactWhereInput(
                fullName = contains("OR169C-"),
                OR = mutableListOf(ReferringOrganisationContactWhereInput(email = contains("or169-hit"))),
            )
        assertThat(visible(contacts.findAll(where.build()).map { it.id }, fixtures)).containsExactly(both.id)
    }

    private fun kit(
        model: String,
        lotId: String? = null,
        subStatus: KitSubStatus = KitSubStatus(),
    ) = kits.save(Kit(type = KitType.LAPTOP, status = KitStatus.DONATION_NEW, model = model, age = 0, lotId = lotId, subStatus = subStatus))

    @Test
    fun `KitWhereInput - a field beside OR narrows instead of being swallowed`() {
        val both = kit("OR169K-both", lotId = "OR169-LOT")
        val modelOnly = kit("OR169K-model", lotId = "OTHER")
        val orOnly = kit("Elsewhere K", lotId = "OR169-LOT")
        val fixtures = listOf(both, modelOnly, orOnly).map { it.id }

        val where = KitWhereInput(model = contains("OR169K-"), OR = mutableListOf(KitWhereInput(lotId = contains("OR169-LOT"))))
        assertThat(visible(kits.findAll(where.build()).map { it.id }, fixtures)).containsExactly(both.id)

        val emptyOr = KitWhereInput(model = contains("OR169K-"), OR = mutableListOf())
        assertThat(visible(kits.findAll(emptyOr.build()).map { it.id }, fixtures)).containsExactlyInAnyOrder(both.id, modelOnly.id)
    }

    @Test
    fun `KitSubStatusWhereInput - a flag beside OR narrows instead of being swallowed`() {
        val both = kit("OR169S-a", subStatus = KitSubStatus(wipeFailed = true, lockedToUser = true))
        val flagOnly = kit("OR169S-b", subStatus = KitSubStatus(wipeFailed = true, lockedToUser = false))
        val orOnly = kit("OR169S-c", subStatus = KitSubStatus(wipeFailed = false, lockedToUser = true))
        val fixtures = listOf(both, flagOnly, orOnly).map { it.id }

        val where =
            KitWhereInput(
                model = contains("OR169S-"),
                subStatus =
                    KitSubStatusWhereInput(
                        wipeFailed = BooleanComparison(_eq = true),
                        OR = mutableListOf(KitSubStatusWhereInput(lockedToUser = BooleanComparison(_eq = true))),
                    ),
            )
        assertThat(visible(kits.findAll(where.build()).map { it.id }, fixtures)).containsExactly(both.id)
    }
}
