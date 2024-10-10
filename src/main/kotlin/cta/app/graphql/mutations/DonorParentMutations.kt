package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.NotNull
import cta.app.DonorParent
import cta.app.DonorParentRepository
import cta.app.QDonorParent
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Component
@Validated
@PreAuthorize("hasAnyAuthority('write:dropPoints')")
@Transactional
class DonorParentMutations(
    private val dropPoints: DonorParentRepository
) : GraphQLMutationResolver {

    fun createDonorParent(@Valid data: CreateDonorParentInput): DonorParent {
        return dropPoints.save(data.entity)
    }

    fun updateDonorParent(@Valid data: UpdateDonorParentInput): DonorParent {
        val entity = dropPoints.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a drop point with id: ${data.id}")
        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:dropPoints')")
    fun deleteDonorParent(id: Long): Boolean {
        val dropPoint = dropPoints.findById(id).toNullable()
            ?: throw EntityNotFoundException("No drop point with id: $id")
        dropPoint.donors.forEach { dropPoint.removeDonor(it) }
        dropPoints.delete(dropPoint)
        return true
    }
}

data class CreateDonorParentInput(
    @get:NotBlank
    val name: String,
    val address: String,
    val website: String
) {
    val entity by lazy {
        DonorParent(
            name = name,
            address = address,
            website = website
        )
    }
}

data class UpdateDonorParentInput(
    @get:NotNull
    val id: Long,
    var name: String,
    val address: String,
    val website: String
) {
    fun apply(entity: DonorParent): DonorParent {
        val self = this
        return entity.apply {
            name = self.name
            address = self.address
            website = self.website
        }
    }
}
