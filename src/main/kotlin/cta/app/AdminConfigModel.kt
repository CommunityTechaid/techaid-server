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

@Entity
@Table(name = "admin_config")
class AdminConfig(
    @Id
    val id: Long = 1L, //singleton
    
    var canPublicRequestSIMCard: Boolean = true,

    var canPublicRequestLaptop: Boolean = true,

    var canPublicRequestPhone: Boolean = true,

    var canPublicRequestBroadbandHub: Boolean = true,

    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now()
)

interface AdminConfigRepository : JpaRepository<AdminConfig, Long> {
    @Query("SELECT c FROM Admin_Config c WHERE c.id = 1")
    fun findAdminConfig(): AdminConfig

    @Modifying
    @Query("UPDATE Admin_Config c " + 
           "SET c.canPublicRequestSIMCard = :canPublicRequestSIMCard, " +
           "c.canPublicRequestLaptop = :canPublicRequestLaptop, " +
           "c.canPublicRequestPhone = :canPublicRequestPhone, " +
           "c.canPublicRequestBroadbandHub = :canPublicRequestBroadbandHub " + 
           "WHERE c.id = 1")
    fun updateAdminConfig(
        canPublicRequestSIMCard: Boolean,
        canPublicRequestLaptop: Boolean,
        canPublicRequestPhone: Boolean,
        canPublicRequestBroadbandHub: Boolean
    ): Int
}