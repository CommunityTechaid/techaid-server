package cta.app

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Pins the SORT contract of the five Hibernate @Formula count fields:
 * Donor.kitCount, DonorParent.donorCount, DeviceRequest.kitCount,
 * ReferringOrganisation.requestCount and ReferringOrganisationContact.requestCount.
 *
 * Why this test exists: the dashboard's DataTables send the clicked column's `data`
 * key straight through as a server-side sort key
 * (`sort: [{key: 'kitCount', value: 'desc'}]`, see techaid-dashboard
 * donor-index.component.ts ajax handler). PaginationInput.create() feeds that key
 * into Spring Data `Sort.by(...)`, which resolves it as a JPA property path.
 *
 * Because these fields are @Formula (real SQL in the generated statement) the sort
 * currently works. Converting any of them to @Transient — e.g. to resolve the counts
 * via a batched GraphQL DataLoader — would make the property unresolvable and every
 * sort-by-count request would fail. This test is the guard on that decision.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
class FormulaCountSortContractTest {
    @MockBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var donors: DonorRepository

    @Autowired
    lateinit var donorParents: DonorParentRepository

    @Autowired
    lateinit var kits: KitRepository

    @Autowired
    lateinit var organisations: ReferringOrganisationRepository

    @Autowired
    lateinit var contacts: ReferringOrganisationContactRepository

    @Autowired
    lateinit var deviceRequests: DeviceRequestRepository

    private fun query(q: String) =
        mockMvc.perform(
            post("/graphql")
                .with(jwt().authorities(SimpleGrantedAuthority("app:admin")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"$q"}"""),
        )

    private fun newDonor(name: String) =
        donors.save(
            Donor(
                name = name,
                postCode = "N1 1AA",
                phoneNumber = "000",
                email = "$name@example.com",
                referral = "test",
            ),
        )

    private fun newKit(
        donor: Donor? = null,
        request: DeviceRequest? = null,
    ) = kits.save(Kit(model = "probe", age = 1, donor = donor, deviceRequest = request))

    private fun newRequest(
        contact: ReferringOrganisationContact,
        status: DeviceRequestStatus,
    ) = deviceRequests.save(
        DeviceRequest(
            deviceRequestItems = DeviceRequestItems(phones = 1),
            status = status,
            referringOrganisationContact = contact,
            clientRef = "ref-${status.name}",
            borough = "Camden",
            details = "probe",
            deviceRequestNeeds = null,
        ),
    )

    @BeforeEach
    fun seed() {
        kits.deleteAll()
        deviceRequests.deleteAll()
        donors.deleteAll()
        donorParents.deleteAll()
        contacts.deleteAll()
        organisations.deleteAll()
    }

    @Test
    fun `donorsConnection can sort by the kitCount formula`() {
        val few = newDonor("few")
        val many = newDonor("many")
        newKit(donor = few)
        repeat(3) { newKit(donor = many) }

        query(
            "query { donorsConnection(page: {sort: [{key: \\\"kitCount\\\", value: \\\"DESC\\\"}]}, " +
                "where: {}) { content { name kitCount } } }",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.donorsConnection.content[0].name").value("many"))
            .andExpect(jsonPath("$.data.donorsConnection.content[0].kitCount").value(3))
            .andExpect(jsonPath("$.data.donorsConnection.content[1].name").value("few"))
            .andExpect(jsonPath("$.data.donorsConnection.content[1].kitCount").value(1))
    }

    @Test
    fun `donorParentsConnection can sort by the donorCount formula`() {
        val empty = donorParents.save(DonorParent(name = "empty", address = "a", website = "w"))
        val full = donorParents.save(DonorParent(name = "full", address = "a", website = "w"))
        val d1 = newDonor("d1")
        val d2 = newDonor("d2")
        d1.donorParent = full
        d2.donorParent = full
        donors.saveAll(listOf(d1, d2))

        query(
            "query { donorParentsConnection(page: {sort: [{key: \\\"donorCount\\\", value: \\\"DESC\\\"}]}, " +
                "where: {}) { content { name donorCount } } }",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.donorParentsConnection.content[0].name").value("full"))
            .andExpect(jsonPath("$.data.donorParentsConnection.content[0].donorCount").value(2))
            .andExpect(jsonPath("$.data.donorParentsConnection.content[1].name").value(empty.name))
            .andExpect(jsonPath("$.data.donorParentsConnection.content[1].donorCount").value(0))
    }

    @Test
    fun `deviceRequestConnection can sort by the kitCount formula`() {
        val org = organisations.save(ReferringOrganisation(name = "org"))
        val contact =
            contacts.save(
                ReferringOrganisationContact(
                    fullName = "c",
                    email = "c@example.com",
                    phoneNumber = "0",
                    address = "a",
                    referringOrganisation = org,
                ),
            )
        val withKits = newRequest(contact, DeviceRequestStatus.NEW)
        newRequest(contact, DeviceRequestStatus.PROCESSING_ON_HOLD)
        repeat(2) { newKit(request = withKits) }

        query(
            "query { deviceRequestConnection(page: {sort: [{key: \\\"kitCount\\\", value: \\\"DESC\\\"}]}, " +
                "where: {}) { content { kitCount } } }",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.deviceRequestConnection.content[0].kitCount").value(2))
            .andExpect(jsonPath("$.data.deviceRequestConnection.content[1].kitCount").value(0))
    }

    @Test
    fun `referringOrganisationsConnection can sort by the requestCount formula`() {
        val busy = organisations.save(ReferringOrganisation(name = "busy"))
        val quiet = organisations.save(ReferringOrganisation(name = "quiet"))
        val busyContact =
            contacts.save(
                ReferringOrganisationContact(
                    fullName = "busy-c",
                    email = "busy@example.com",
                    phoneNumber = "0",
                    address = "a",
                    referringOrganisation = busy,
                ),
            )
        newRequest(busyContact, DeviceRequestStatus.NEW)

        query(
            "query { referringOrganisationsConnection(page: {sort: [{key: \\\"requestCount\\\", value: \\\"DESC\\\"}]}, " +
                "where: {}) { content { name requestCount } } }",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.referringOrganisationsConnection.content[0].name").value("busy"))
            .andExpect(jsonPath("$.data.referringOrganisationsConnection.content[0].requestCount").value(1))
            .andExpect(jsonPath("$.data.referringOrganisationsConnection.content[1].name").value(quiet.name))
            .andExpect(jsonPath("$.data.referringOrganisationsConnection.content[1].requestCount").value(0))
    }

    @Test
    fun `referringOrganisationContactsConnection can sort by the requestCount formula`() {
        val org = organisations.save(ReferringOrganisation(name = "org"))
        val busy =
            contacts.save(
                ReferringOrganisationContact(
                    fullName = "busy",
                    email = "busy@example.com",
                    phoneNumber = "0",
                    address = "a",
                    referringOrganisation = org,
                ),
            )
        val quiet =
            contacts.save(
                ReferringOrganisationContact(
                    fullName = "quiet",
                    email = "quiet@example.com",
                    phoneNumber = "0",
                    address = "a",
                    referringOrganisation = org,
                ),
            )
        newRequest(busy, DeviceRequestStatus.NEW)
        newRequest(busy, DeviceRequestStatus.PROCESSING_ON_HOLD)
        // Terminal statuses must NOT be counted by the contact-level formula.
        newRequest(quiet, DeviceRequestStatus.REQUEST_CANCELLED)
        newRequest(quiet, DeviceRequestStatus.REQUEST_COMPLETED)
        newRequest(quiet, DeviceRequestStatus.REQUEST_DECLINED)

        query(
            "query { referringOrganisationContactsConnection(page: {sort: [{key: \\\"requestCount\\\", value: \\\"DESC\\\"}]}, " +
                "where: {}) { content { fullName requestCount } } }",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.referringOrganisationContactsConnection.content[0].fullName").value("busy"))
            .andExpect(jsonPath("$.data.referringOrganisationContactsConnection.content[0].requestCount").value(2))
            .andExpect(jsonPath("$.data.referringOrganisationContactsConnection.content[1].fullName").value(quiet.fullName))
            .andExpect(jsonPath("$.data.referringOrganisationContactsConnection.content[1].requestCount").value(0))
    }
}
