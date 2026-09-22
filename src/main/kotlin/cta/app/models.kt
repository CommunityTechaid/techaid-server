package cta.app

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import org.hibernate.envers.RevisionEntity
import org.hibernate.envers.RevisionNumber
import org.hibernate.envers.RevisionTimestamp

/*
 * This used to carry @Converts mapping attributeName "json"/"jsonb" to hypersistence's
 * JsonStringType/JsonBinaryType. That was dead decoration: those classes are Hibernate UserTypes,
 * not JPA AttributeConverters, and BaseEntity has no attributes for the names to match anyway. It
 * only compiled because Jakarta Persistence 3.1 declared Convert.converter() as a raw Class;
 * 3.2 (Jakarta EE 11, Boot 4) tightened it to Class<? extends AttributeConverter<?,?>> and the
 * compiler finally rejected it.
 *
 * The real jsonb mapping is per-field and native to Hibernate 6+: see @JdbcTypeCode(SqlTypes.JSON)
 * on Kit.attributes. Nothing needs hypersistence-utils, so the dependency went with this.
 */
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
