package cta.app

import java.time.Instant
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.querydsl.QuerydslPredicateExecutor

@Entity
@Table(name = "admin_config")
class AdminConfig(
    @Id
    val id: Long = 1L, //singleton
    
    var canPublicRequestSIMCard: Boolean = true,

    var canPublicRequestLaptop: Boolean = true,

    var canPublicRequestPhone: Boolean = true,

    var canPublicRequestBroadbandHub: Boolean = true,

    var canPublicRequestTablet: Boolean = true,

    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now()
)

interface AdminConfigRepository : JpaRepository<AdminConfig, Long>, QuerydslPredicateExecutor<AdminConfig> {
    /**
     * Finds the admin configuration. This is a singleton entity, so it always returns the same instance.
     */
    @Query("SELECT * FROM Admin_Config c WHERE c.id = 1", 
    nativeQuery = true)
    fun getAdminConfig(): AdminConfig

    @Modifying
    @Query("UPDATE Admin_Config c " + 
           "SET c.canPublicRequestSIMCard = :canPublicRequestSIMCard, " +
           "c.canPublicRequestLaptop = :canPublicRequestLaptop, " +
           "c.canPublicRequestPhone = :canPublicRequestPhone, " +
           "c.canPublicRequestBroadbandHub = :canPublicRequestBroadbandHub, " + 
           "c.canPublicRequestTablet = :canPublicRequestTablet " + 
           "WHERE c.id = 1",
           nativeQuery = true)
    fun updateAdminConfig(
        canPublicRequestSIMCard: Boolean,
        canPublicRequestLaptop: Boolean,
        canPublicRequestPhone: Boolean,
        canPublicRequestBroadbandHub: Boolean,
        canPublicRequestTablet: Boolean
    ): Int
}