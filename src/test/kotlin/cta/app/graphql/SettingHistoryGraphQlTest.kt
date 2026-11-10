package cta.app.graphql

import cta.app.BoroughGroupRepository
import cta.app.ReferringOrganisation
import cta.app.ReferringOrganisationRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * Admin-panel setting history (#327) through /graphql: each mutation records exactly the changed
 * fields with the admin's email and name, no-op saves record nothing, a rejected borough save rolls
 * its history back, and `settingChanges` pages newest-first behind app:admin.
 *
 * Forks its own context (and embedded database) because it rewrites the shared borough config and
 * admin_config, which other suites read; the method order makes the borough sequence explicit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@TestPropertySource(properties = ["spring.application.name=techaid-server-setting-history-test"])
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SettingHistoryGraphQlTest {
    @MockitoBean
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var boroughGroups: BoroughGroupRepository

    @Autowired
    lateinit var organisations: ReferringOrganisationRepository

    private val adminEmail = "admin@example.com"
    private val adminName = "Ada Admin"

    private var mark = 0L

    @BeforeEach
    fun markHistory() {
        mark = jdbc.queryForObject("select coalesce(max(id), 0) from admin_setting_history", Long::class.java)!!
        jdbc.update(
            "insert into admin_config (id, can_public_request_broadband_hub, can_public_request_desktop, " +
                "can_public_request_laptop, can_public_request_phone, can_public_request_tablet, can_public_requestsimcard) " +
                "values (1, true, true, true, true, true, true) on conflict (id) do nothing",
        )
    }

    /** History rows written since [markHistory], oldest first. */
    private fun newRows(): List<Map<String, Any?>> = jdbc.queryForList("select * from admin_setting_history where id > ? order by id", mark)

    private fun gql(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        authorities: List<String>? = listOf("app:admin"),
    ): JsonNode {
        var request =
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("query" to query, "variables" to variables)))
        if (authorities != null) {
            request =
                request.with(
                    jwt()
                        .jwt {
                            it.claim("https://test.example.com/email", adminEmail)
                            it.claim("https://test.example.com/name", adminName)
                        }.authorities(authorities.map { SimpleGrantedAuthority(it) }),
                )
        }
        return objectMapper.readTree(
            mockMvc
                .perform(request)
                .andReturn()
                .response.contentAsString,
        )
    }

    private fun ok(response: JsonNode): JsonNode {
        assertThat(response.get("errors")).`as`(response.toString()).isNull()
        return response.get("data")
    }

    private fun setFlag(
        key: String,
        enabled: Boolean,
    ) = ok(
        gql(
            "mutation(\$k: String!, \$e: Boolean!) { updateFeatureFlag(data: {key: \$k, enabled: \$e}) { key } }",
            mapOf(
                "k" to key,
                "e" to enabled,
            ),
        ),
    )

    private fun assertActor(row: Map<String, Any?>) {
        assertThat(row["source"]).isEqualTo("USER")
        assertThat(row["changed_by_email"]).isEqualTo(adminEmail)
        assertThat(row["changed_by_name"]).isEqualTo(adminName)
    }

    @Test
    @Order(0)
    fun `migration backfills one CREATED row per existing flag`() {
        val flags = jdbc.queryForList("select flag_key, enabled from feature_flags")
        val rows = jdbc.queryForList("select * from admin_setting_history where source = 'MIGRATION'")
        assertThat(rows.map { it["setting_key"] }).containsExactlyInAnyOrderElementsOf(flags.map { it["flag_key"] })
        rows.forEach { row ->
            assertThat(row["change_type"]).isEqualTo("CREATED")
            assertThat(row["field"]).isEqualTo("enabled")
            assertThat(row["old_value"]).isNull()
            assertThat(row["changed_by_email"]).isNull()
            assertThat(row["subject_label"]).isEqualTo(row["setting_key"])
            val enabled = flags.first { it["flag_key"] == row["setting_key"] }["enabled"]
            assertThat(row["new_value"]).isEqualTo(enabled.toString())
        }
    }

    @Test
    @Order(1)
    fun `updateFeatureFlag records CREATED then UPDATED, and nothing for a no-op`() {
        setFlag("history-probe", true)
        setFlag("history-probe", true)
        setFlag("history-probe", false)

        val rows = newRows()
        assertThat(rows.map { it["change_type"] }).containsExactly("CREATED", "UPDATED")
        assertThat(rows.map { it["old_value"] }).containsExactly(null, "true")
        assertThat(rows.map { it["new_value"] }).containsExactly("true", "false")
        rows.forEach {
            assertActor(it)
            assertThat(it["setting_type"]).isEqualTo("FEATURE_FLAG")
            assertThat(it["setting_key"]).isEqualTo("history-probe")
            assertThat(it["subject_id"]).isNull()
        }
    }

    @Test
    @Order(2)
    fun `partial updateAdminConfig records only the changed fields`() {
        // Laptop is already true: sent but unchanged, so not recorded.
        ok(gql("mutation { updateAdminConfig(data: {canPublicRequestLaptop: true, membershipGraceRequests: 5}) { id } }"))
        val rows = newRows()
        assertThat(rows).hasSize(1)
        assertThat(rows[0]["setting_type"]).isEqualTo("ADMIN_CONFIG")
        assertThat(rows[0]["setting_key"]).isEqualTo("membershipGraceRequests")
        assertThat(rows[0]["field"]).isEqualTo("membershipGraceRequests")
        assertThat(listOf(rows[0]["old_value"], rows[0]["new_value"])).containsExactly("2", "5")
        assertActor(rows[0])
    }

    @Test
    @Order(3)
    fun `every admin config field is recorded`() {
        ok(
            gql(
                "mutation { updateAdminConfig(data: {canPublicRequestSIMCard: false, canPublicRequestLaptop: false, " +
                    "canPublicRequestPhone: false, canPublicRequestBroadbandHub: false, canPublicRequestTablet: false, " +
                    "canPublicRequestDesktop: false, membershipOverLimitMode: BLOCK, membershipGraceRequests: 0}) { id } }",
            ),
        )
        val rows = newRows().associateBy { it["field"] }
        assertThat(rows.keys).containsExactlyInAnyOrder(
            "canPublicRequestSIMCard",
            "canPublicRequestLaptop",
            "canPublicRequestPhone",
            "canPublicRequestBroadbandHub",
            "canPublicRequestTablet",
            "canPublicRequestDesktop",
            "membershipOverLimitMode",
            "membershipGraceRequests",
        )
        assertThat(rows.getValue("canPublicRequestTablet")["new_value"]).isEqualTo("false")
        assertThat(listOf(rows.getValue("membershipOverLimitMode")["old_value"], rows.getValue("membershipOverLimitMode")["new_value"]))
            .containsExactly("GRACE", "BLOCK")
        assertThat(rows.getValue("membershipGraceRequests")["new_value"]).isEqualTo("0")
        rows.values.forEach { assertActor(it) }
    }

    private val saveBoroughs = "mutation(\$d: SaveBoroughAvailabilityInput!) { saveBoroughAvailability(data: \$d) { id name } }"

    /** The current borough config as a save input, i.e. a save that changes nothing. */
    private fun currentGroups(): MutableList<MutableMap<String, Any?>> =
        boroughGroups
            .findAll()
            .sortedBy { it.id }
            .map { group ->
                mutableMapOf<String, Any?>(
                    "id" to group.id,
                    "name" to group.name,
                    "boroughs" to group.boroughs.toList().reversed(),
                    "status" to group.status.name,
                    "maxPerReferee" to group.maxPerReferee,
                    "availability" to group.availability.map { mapOf("deviceType" to it.deviceType, "mode" to it.mode.name) },
                )
            }.toMutableList()

    private fun save(
        groups: List<Map<String, Any?>>,
        exceptions: List<Map<String, Any?>>,
    ) = gql(saveBoroughs, mapOf("d" to mapOf("groups" to groups, "exceptions" to exceptions)))

    private fun groupId(name: String) = boroughGroups.findAll().single { it.name == name }.id

    private fun org(name: String) = organisations.save(ReferringOrganisation(name = name))

    private fun exception(
        orgId: Long,
        groupId: Long,
        limit: Int,
    ) = mapOf("organisationId" to orgId, "boroughGroupId" to groupId, "maxPerReferee" to limit)

    @Test
    @Order(10)
    fun `saveBoroughAvailability records cells, groups and exceptions, and nothing on an identical re-save`() {
        val ls = groupId("Lambeth & Southwark")
        val orgA = org("History Org A")
        val orgB = org("History Org B")

        // 1. Add two exceptions, turn Lambeth & Southwark laptops OFF, create a group.
        val groups = currentGroups()
        val lsGroup = groups.single { it["id"] == ls }
        lsGroup["availability"] =
            (lsGroup["availability"] as List<*>).map {
                it as Map<*, *>
                if (it["deviceType"] == "laptops") mapOf("deviceType" to "laptops", "mode" to "OFF") else it
            }
        groups +=
            mutableMapOf(
                "name" to "Hackney",
                "boroughs" to listOf("Hackney"),
                "status" to "DRAFT",
                "maxPerReferee" to 2,
                "availability" to listOf(mapOf("deviceType" to "phones", "mode" to "ON")),
            )
        ok(save(groups, listOf(exception(orgA.id, ls, 5), exception(orgB.id, ls, 1))))
        val hackney = groupId("Hackney")

        val rows = newRows()
        val cell = rows.single { it["setting_type"] == "BOROUGH_AVAILABILITY" && it["setting_key"] == "$ls:laptops" }
        assertThat(listOf(cell["change_type"], cell["old_value"], cell["new_value"])).containsExactly("UPDATED", "ON", "OFF")
        assertThat(cell["subject_label"]).isEqualTo("Lambeth & Southwark")
        assertThat(cell["subject_id"]).isEqualTo(ls)

        val created = rows.filter { it["subject_id"] == hackney && it["setting_type"] != "LIMIT_EXCEPTION" }
        assertThat(created).allMatch { it["change_type"] == "CREATED" && it["old_value"] == null && it["subject_label"] == "Hackney" }
        assertThat(created.filter { it["setting_type"] == "BOROUGH_GROUP" }.map { it["field"] to it["new_value"] })
            .containsExactlyInAnyOrder("name" to "Hackney", "status" to "DRAFT", "maxPerReferee" to "2", "boroughs" to "Hackney")
        // Every device type gets a mode (omitted ones default to OFF), one CREATED row each.
        assertThat(created.count { it["setting_type"] == "BOROUGH_AVAILABILITY" }).isEqualTo(8)

        val exceptions = rows.filter { it["setting_type"] == "LIMIT_EXCEPTION" }
        assertThat(exceptions.map { Triple(it["setting_key"], it["subject_label"], it["new_value"]) }).containsExactlyInAnyOrder(
            Triple("$ls:${orgA.id}", "History Org A", "5"),
            Triple("$ls:${orgB.id}", "History Org B", "1"),
        )
        assertThat(exceptions).allMatch { it["change_type"] == "CREATED" && it["field"] == "maxPerReferee" }
        assertThat(exceptions.map { it["subject_id"] }).containsExactlyInAnyOrder(orgA.id, orgB.id)
        assertThat(rows).hasSize(1 + 4 + 8 + 2)
        rows.forEach { assertActor(it) }

        // 2. Identical re-save: exceptions are deleted and re-inserted internally, yet nothing is recorded.
        val identical = listOf(exception(orgB.id, ls, 1), exception(orgA.id, ls, 5))
        mark = jdbc.queryForObject("select max(id) from admin_setting_history", Long::class.java)!!
        ok(save(currentGroups(), identical))
        assertThat(newRows()).isEmpty()

        // 3. Change one exception, remove the other, delete the new group.
        ok(save(currentGroups().filter { it["id"] != hackney }, listOf(exception(orgA.id, ls, 7))))
        val third = newRows()
        val changed = third.single { it["setting_key"] == "$ls:${orgA.id}" }
        assertThat(listOf(changed["change_type"], changed["old_value"], changed["new_value"])).containsExactly("UPDATED", "5", "7")
        val removed = third.single { it["setting_key"] == "$ls:${orgB.id}" }
        assertThat(listOf(removed["change_type"], removed["old_value"], removed["new_value"])).containsExactly("DELETED", "1", null)
        val deleted = third.filter { it["subject_id"] == hackney && it["setting_type"] != "LIMIT_EXCEPTION" }
        assertThat(deleted).hasSize(4 + 8)
        assertThat(deleted).allMatch { it["change_type"] == "DELETED" && it["new_value"] == null && it["subject_label"] == "Hackney" }
        assertThat(third).hasSize(2 + 12)
    }

    @Test
    @Order(11)
    fun `a rejected saveBoroughAvailability writes no history`() {
        val ls = groupId("Lambeth & Southwark")
        val groups = currentGroups()
        groups.single { it["id"] == ls }["maxPerReferee"] = 99

        // Fails after the groups are written, on the unknown organisation: the whole transaction rolls back.
        val response = save(groups, listOf(exception(999_999_999L, ls, 1)))
        assertThat(response.get("errors")).isNotNull
        assertThat(newRows()).isEmpty()
        assertThat(boroughGroups.findById(ls).get().maxPerReferee).isNotEqualTo(99)

        // Fails in validate(), before anything is written.
        val invalid = currentGroups()
        invalid.single { it["id"] == ls }["status"] = "NOPE"
        assertThat(save(invalid, emptyList()).get("errors")).isNotNull
        assertThat(newRows()).isEmpty()
    }

    private val historyQuery =
        "query(\$t: String, \$k: String, \$p: Int, \$s: Int) { settingChanges(settingType: \$t, settingKey: \$k, page: \$p, size: \$s) " +
            "{ totalElements totalPages number size content { id changedAt source changedByEmail changedByName settingType " +
            "settingKey subjectId subjectLabel field changeType oldValue newValue } } }"

    private fun history(
        type: String? = null,
        key: String? = null,
        page: Int = 0,
        size: Int = 50,
        authorities: List<String>? = listOf("app:admin"),
    ) = gql(historyQuery, mapOf("t" to type, "k" to key, "p" to page, "s" to size), authorities)

    @Test
    @Order(20)
    fun `a migration-style row round-trips with source MIGRATION and no user`() {
        jdbc.update(
            "insert into admin_setting_history " +
                "(source, setting_type, setting_key, subject_label, field, change_type, old_value, new_value) " +
                "values ('MIGRATION', 'FEATURE_FLAG', 'migrated-flag', 'migrated-flag', 'enabled', 'UPDATED', 'false', 'true')",
        )
        val page = ok(history(type = "FEATURE_FLAG", key = "migrated-flag")).get("settingChanges")
        assertThat(page.get("totalElements").asInt()).isEqualTo(1)
        val row = page.get("content").get(0)
        assertThat(row.get("source").asString()).isEqualTo("MIGRATION")
        assertThat(row.get("changedByEmail").isNull).isTrue()
        assertThat(row.get("changedByName").isNull).isTrue()
        assertThat(row.get("subjectId").isNull).isTrue()
        assertThat(row.get("changeType").asString()).isEqualTo("UPDATED")
        assertThat(row.get("oldValue").asString()).isEqualTo("false")
        assertThat(row.get("newValue").asString()).isEqualTo("true")
        Instant.parse(row.get("changedAt").asString())
    }

    @Test
    @Order(21)
    fun `settingChanges pages newest first and filters by type and key`() {
        setFlag("paging-probe", true)
        setFlag("paging-probe", false)
        setFlag("paging-probe", true)

        val first = ok(history(type = "FEATURE_FLAG", key = "paging-probe", size = 2)).get("settingChanges")
        assertThat(first.get("totalElements").asInt()).isEqualTo(3)
        assertThat(first.get("totalPages").asInt()).isEqualTo(2)
        assertThat(first.get("number").asInt()).isEqualTo(0)
        assertThat(first.get("size").asInt()).isEqualTo(2)
        val second = ok(history(type = "FEATURE_FLAG", key = "paging-probe", page = 1, size = 2)).get("settingChanges")
        val all = (first.get("content").toList() + second.get("content").toList())
        assertThat(all.map { it.get("newValue").asString() }).containsExactly("true", "false", "true")
        assertThat(all.map { it.get("changeType").asString() }).containsExactly("UPDATED", "UPDATED", "CREATED")
        assertThat(all.map { it.get("id").asString().toLong() }).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(all[0].get("changedByEmail").asString()).isEqualTo(adminEmail)
        assertThat(all[0].get("changedByName").asString()).isEqualTo(adminName)

        val byType = ok(history(type = "ADMIN_CONFIG", size = 200)).get("settingChanges")
        assertThat(byType.get("content").toList()).isNotEmpty.allMatch { it.get("settingType").asString() == "ADMIN_CONFIG" }

        val byKey = ok(history(key = "membershipGraceRequests")).get("settingChanges")
        assertThat(byKey.get("totalElements").asInt()).isEqualTo(2)
        assertThat(byKey.get("content").toList().map { it.get("newValue").asString() }).containsExactly("0", "5")

        val unfiltered = ok(history(size = 200)).get("settingChanges").get("content").toList()
        val instants = unfiltered.map { Instant.parse(it.get("changedAt").asString()) }
        assertThat(instants).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    @Order(22)
    fun `settingChanges is rejected for anonymous and non-admin callers`() {
        listOf(null, listOf("read:organisations", "write:organisations")).forEach { authorities ->
            val response = history(authorities = authorities)
            assertThat(
                response
                    .get("errors")
                    .get(0)
                    .get("message")
                    .asString(),
            ).isEqualTo("Access Denied")
            assertThat(response.get("data").isNull).isTrue()
        }
    }

    private val staff = listOf("write:organisations")

    private fun rowsOf(type: String) = newRows().filter { it["setting_type"] == type }

    private fun triple(row: Map<String, Any?>) = Triple(row["change_type"], row["old_value"], row["new_value"])

    @Test
    @Order(30)
    fun `delivery config, windows, blocked dates and day boroughs are recorded, and no-ops are not`() {
        // Seeded by the delivery migration: config row id 1.
        jdbc.update("update delivery_config set days_of_week = '2,4', enabled = true, lead_time_days = 1, advance_days = 4")

        // Adding a third delivery day, given in a different order, is one UPDATED daysOfWeek row.
        ok(
            gql(
                "mutation { updateDeliveryConfig(data: {enabled: true, daysOfWeek: \"5,2,4\", leadTimeDays: 1, advanceDays: 4}) { id } }",
                authorities = staff,
            ),
        )
        var rows = rowsOf("DELIVERY_CONFIG")
        assertThat(rows).hasSize(1)
        assertThat(rows[0]["setting_key"]).isEqualTo("daysOfWeek")
        assertThat(triple(rows[0])).isEqualTo(Triple("UPDATED", "2,4", "2,4,5"))
        assertActor(rows[0])
        assertThat(newRows()).hasSize(1)

        // The same save again, in another order, records nothing.
        mark = jdbc.queryForObject("select max(id) from admin_setting_history", Long::class.java)!!
        ok(
            gql(
                "mutation { updateDeliveryConfig(data: {enabled: true, daysOfWeek: \"4,5,2\", leadTimeDays: 1, advanceDays: 4}) { id } }",
                authorities = staff,
            ),
        )
        assertThat(newRows()).isEmpty()

        // A new window records CREATED per field; changing capacity 4 -> 2 records exactly that.
        val created =
            ok(
                gql(
                    "mutation(\$d: DeliveryWindowInput!) { saveDeliveryWindow(data: \$d) { id } }",
                    mapOf("d" to mapOf("name" to "10-4", "startTime" to "10:00", "endTime" to "16:00", "capacity" to 4)),
                    staff,
                ),
            ).get("saveDeliveryWindow").get("id").asString()
        rows = rowsOf("DELIVERY_WINDOW")
        assertThat(rows).allMatch { it["change_type"] == "CREATED" && it["subject_label"] == "10-4" && it["setting_key"] == created }
        assertThat(rows.single { it["field"] == "capacity" }["new_value"]).isEqualTo("4")

        mark = jdbc.queryForObject("select max(id) from admin_setting_history", Long::class.java)!!
        ok(
            gql(
                "mutation(\$d: DeliveryWindowInput!) { saveDeliveryWindow(data: \$d) { id } }",
                mapOf("d" to mapOf("id" to created, "name" to "10-4", "startTime" to "10:00", "endTime" to "16:00", "capacity" to 2)),
                staff,
            ),
        )
        rows = newRows()
        assertThat(rows).hasSize(1)
        assertThat(rows[0]["setting_type"]).isEqualTo("DELIVERY_WINDOW")
        assertThat(rows[0]["field"]).isEqualTo("capacity")
        assertThat(triple(rows[0])).isEqualTo(Triple("UPDATED", "4", "2"))
        assertThat(rows[0]["subject_label"]).isEqualTo("10-4")
        assertActor(rows[0])

        // Blocked dates: add, re-block with a new reason, remove.
        mark = jdbc.queryForObject("select max(id) from admin_setting_history", Long::class.java)!!
        val block = "mutation(\$d: DeliveryBlockedDateInput!) { addDeliveryBlockedDate(data: \$d) { id } }"
        val blockedId =
            ok(
                gql(block, mapOf("d" to mapOf("date" to "2031-12-25", "reason" to "Xmas")), staff),
            ).get("addDeliveryBlockedDate").get("id").asString()
        ok(gql(block, mapOf("d" to mapOf("date" to "2031-12-25", "reason" to "Xmas")), staff))
        ok(gql(block, mapOf("d" to mapOf("date" to "2031-12-25", "reason" to "Christmas")), staff))
        ok(gql("mutation { deleteDeliveryBlockedDate(id: \"$blockedId\") }", authorities = staff))
        rows = rowsOf("DELIVERY_BLOCKED_DATE")
        assertThat(rows.map { triple(it) }).containsExactly(
            Triple("CREATED", null, "Xmas"),
            Triple("UPDATED", "Xmas", "Christmas"),
            Triple("DELETED", "Christmas", null),
        )
        assertThat(rows.map { it["setting_key"] }.toSet()).containsExactly("2031-12-25")

        // Borough days: restrict Tuesday, change it, then clear it. (The sets are disjoint on purpose:
        // re-saving an overlapping set trips a unique constraint in setDeliveryDayBoroughs, a bug that
        // predates this change and is out of its scope.)
        mark = jdbc.queryForObject("select max(id) from admin_setting_history", Long::class.java)!!
        val setDay = "mutation(\$b: [String!]!) { setDeliveryDayBoroughs(dayOfWeek: 2, boroughs: \$b) { dayOfWeek } }"
        ok(gql(setDay, mapOf("b" to listOf("Southwark", "Lambeth")), staff))
        ok(gql(setDay, mapOf("b" to listOf("Hackney")), staff))
        ok(gql(setDay, mapOf("b" to emptyList<String>()), staff))
        rows = rowsOf("DELIVERY_DAY_BOROUGH")
        assertThat(rows.map { triple(it) }).containsExactly(
            Triple("CREATED", null, "Lambeth,Southwark"),
            Triple("UPDATED", "Lambeth,Southwark", "Hackney"),
            Triple("DELETED", "Hackney", null),
        )

        // Deleting the window records DELETED rows.
        mark = jdbc.queryForObject("select max(id) from admin_setting_history", Long::class.java)!!
        ok(gql("mutation { deleteDeliveryWindow(id: \"$created\") }", authorities = staff))
        rows = newRows()
        assertThat(rows).hasSize(7).allMatch { it["change_type"] == "DELETED" && it["new_value"] == null && it["subject_label"] == "10-4" }

        // And the history query serves them.
        val page = ok(history(type = "DELIVERY_CONFIG")).get("settingChanges")
        assertThat(
            page
                .get("content")
                .get(0)
                .get("field")
                .asString(),
        ).isEqualTo("daysOfWeek")
    }
}
