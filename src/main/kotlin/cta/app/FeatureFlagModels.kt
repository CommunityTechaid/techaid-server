package cta.app

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * A simple named on/off switch. Seeded by Flyway; toggled from the dashboard's
 * Feature Flags admin page. Read anonymously (featureFlagsPublic) so public pages
 * can gate themselves, and edited by admins.
 */
@Entity
@Table(name = "feature_flags")
class FeatureFlag(
    @Id
    @Column(name = "flag_key")
    var key: String = "",
    var enabled: Boolean = false,
    @UpdateTimestamp
    var updatedAt: Instant = Instant.now(),
)

interface FeatureFlagRepository : JpaRepository<FeatureFlag, String>
