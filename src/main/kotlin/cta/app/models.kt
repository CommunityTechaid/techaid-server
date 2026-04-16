package cta.app

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import io.hypersistence.utils.hibernate.type.json.JsonStringType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converts
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import org.hibernate.envers.RevisionEntity
import org.hibernate.envers.RevisionNumber
import org.hibernate.envers.RevisionTimestamp

@Converts(
    Convert(attributeName = "json", converter = JsonStringType::class),
    Convert(attributeName = "jsonb", converter = JsonBinaryType::class),
)
@MappedSuperclass
class BaseEntity

/*
* This entity is used to hold the information of audits used by Hibernate Enver.
* It is completely managed by the plugin. The id and the timestamp are mandatory fields
*/
@Entity
@Table(name = "custom_rev_info")
@RevisionEntity(CustomRevisionEntityListener::class)
class CustomRevisionInfo {
    @Id
    @GeneratedValue
    @RevisionNumber
    val id: Long? = null

    @RevisionTimestamp
    val timestamp: Long? = null

    @Column(name = "custom_user")
    var customUser: String = "user"
}
