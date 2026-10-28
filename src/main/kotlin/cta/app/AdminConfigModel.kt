package cta.app

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.time.Instant

@Entity
@Table(name = "admin_config")
class AdminConfig(
    @Id
    val id: Long = 1L, // singleton
    var canPublicRequestSIMCard: Boolean = true,
    var canPublicRequestLaptop: Boolean = true,
    var canPublicRequestPhone: Boolean = true,
    var canPublicRequestBroadbandHub: Boolean = true,
    var canPublicRequestTablet: Boolean = true,
    var canPublicRequestDesktop: Boolean = true,
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
    // What happens to a request made after an organisation's membership allowance is used up
    // (#311). Staff-editable, anonymously readable (the public form picks its wording from it).
    @Enumerated(EnumType.STRING)
    var membershipOverLimitMode: MembershipOverLimitMode = MembershipOverLimitMode.GRACE,
    // How many requests past the cap GRACE mode accepts; unused in BLOCK mode.
    var membershipGraceRequests: Int = 2,
)

interface AdminConfigRepository :
    JpaRepository<AdminConfig, Long>,
    QuerydslPredicateExecutor<AdminConfig> {
    /**
     * Finds the admin configuration. This is a singleton entity, so it always returns the same instance.
     */
    @Query(
        "SELECT * FROM Admin_Config c WHERE c.id = 1",
        nativeQuery = true,
    )
    fun getAdminConfig(): AdminConfig

    // Not called by the updateAdminConfig mutation (which edits the managed entity). It sets the
    // can_public_request* columns only and deliberately does NOT touch the membership columns, so
    // it can never reset them. Keep it that way if it is ever wired up.
    @Modifying
    @Query(
        "UPDATE Admin_Config c " +
            "SET c.canPublicRequestSIMCard = :canPublicRequestSIMCard, " +
            "c.canPublicRequestLaptop = :canPublicRequestLaptop, " +
            "c.canPublicRequestPhone = :canPublicRequestPhone, " +
            "c.canPublicRequestBroadbandHub = :canPublicRequestBroadbandHub, " +
            "c.canPublicRequestTablet = :canPublicRequestTablet, " +
            "c.canPublicRequestDesktop = :canPublicRequestDesktop " +
            "WHERE c.id = 1",
        nativeQuery = true,
    )
    fun updateAdminConfig(
        canPublicRequestSIMCard: Boolean,
        canPublicRequestLaptop: Boolean,
        canPublicRequestPhone: Boolean,
        canPublicRequestBroadbandHub: Boolean,
        canPublicRequestTablet: Boolean,
        canPublicRequestDesktop: Boolean,
    ): Int
}
