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
@Table(name = "config")
class Config(
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

interface ConfigRepository : JpaRepository<Config, Long> {
    @Query("SELECT c FROM Config c WHERE c.id = 1")
    fun findConfig(): Config?

    @Modifying
    @Query("UPDATE Config c SET c.canPublicRequestSIMCard = :canPublicRequestSIMCard, " +
           "c.canPublicRequestLaptop = :canPublicRequestLaptop, " +
           "c.canPublicRequestPhone = :canPublicRequestPhone, " +
           "c.canPublicRequestBroadbandHub = :canPublicRequestBroadbandHub WHERE c.id = 1")
    fun updateConfig(
        canPublicRequestSIMCard: Boolean,
        canPublicRequestLaptop: Boolean,
        canPublicRequestPhone: Boolean,
        canPublicRequestBroadbandHub: Boolean
    ): Int
}