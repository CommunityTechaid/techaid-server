package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.NotNull
import cta.app.DropPoint
import cta.app.DropPointRepository
import cta.app.QDropPoint
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Component
@Validated
@PreAuthorize("hasAnyAuthority('write:dropPoints')")
@Transactional
class DropPointMutations(
    private val dropPoints: DropPointRepository
) : GraphQLMutationResolver {

    fun createDropPoint(@Valid data: CreateDropPointInput): DropPoint {
        return dropPoints.save(data.entity)
    }

    fun updateDropPoint(@Valid data: UpdateDropPointInput): DropPoint {
        val entity = dropPoints.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a drop point with id: ${data.id}")
        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:dropPoints')")
    fun deleteDropPoint(id: Long): Boolean {
        val dropPoint = dropPoints.findById(id).toNullable()
            ?: throw EntityNotFoundException("No drop point with id: $id")
        dropPoint.donors.forEach { dropPoint.removeDonor(it) }
        dropPoints.delete(dropPoint)
        return true
    }
}

data class CreateDropPointInput(
    @get:NotBlank
    val name: String,
    val address: String,
    val website: String
) {
    val entity by lazy {
        DropPoint(
            name = name,
            address = address,
            website = website
        )
    }
}

data class UpdateDropPointInput(
    @get:NotNull
    val id: Long,
    var name: String,
    val address: String,
    val website: String
) {
    fun apply(entity: DropPoint): DropPoint {
        val self = this
        return entity.apply {
            name = self.name
            address = self.address
            website = self.website
        }
    }
}
