package cta.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.NotNull
import cta.app.DonorParent
import cta.app.DonorParentRepository
import cta.app.DonorParentType
import cta.app.QDonorParent
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Component
@Validated
@PreAuthorize("hasAnyAuthority('write:donorParents')")
@Transactional
class DonorParentMutations(
    private val donorParents: DonorParentRepository
) : GraphQLMutationResolver {

    fun createDonorParent(@Valid data: CreateDonorParentInput): DonorParent {
        return donorParents.save(data.entity)
    }

    fun updateDonorParent(@Valid data: UpdateDonorParentInput): DonorParent {
        val entity = donorParents.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a parent donor with id: ${data.id}")
        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:donorParents')")
    fun deleteDonorParent(id: Long): Boolean {
        val donorParent = donorParents.findById(id).toNullable()
            ?: throw EntityNotFoundException("No parent donor with id: $id")
        donorParent.donors.forEach { donorParent.removeDonor(it) }
        donorParents.delete(donorParent)
        return true
    }
}

data class CreateDonorParentInput(
    @get:NotBlank
    val name: String,
    val address: String,
    val website: String,
    val type: DonorParentType
) {
    val entity by lazy {
        DonorParent(
            name = name,
            address = address,
            website = website,
            type = type
        )
    }
}

data class UpdateDonorParentInput(
    @get:NotNull
    val id: Long,
    var name: String,
    val address: String,
    val website: String,
    val type: DonorParentType
    val archived: Boolean? = null
) {
    fun apply(entity: DonorParent): DonorParent {
        val self = this
        return entity.apply {
            name = self.name
            address = self.address
            website = self.website
            type = self.type
            archived = self.archived ?: archived
        }
    }
}
