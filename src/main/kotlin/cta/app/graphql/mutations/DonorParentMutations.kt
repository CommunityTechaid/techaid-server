package cta.app.graphql.mutations


import cta.app.DonorParent
import cta.app.DonorParentRepository
import cta.app.DonorParentType
import cta.toNullable
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:donorParents')")
@Transactional
class DonorParentMutations(
    private val donorParents: DonorParentRepository
) {

    @MutationMapping
    fun createDonorParent(@Argument @Valid data: CreateDonorParentInput): DonorParent {
        return donorParents.save(data.entity)
    }

    @MutationMapping
    fun updateDonorParent(@Argument @Valid data: UpdateDonorParentInput): DonorParent {
        val entity = donorParents.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a parent donor with id: ${data.id}")
        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:donorParents')")
    @MutationMapping
    fun deleteDonorParent(@Argument id: Long): Boolean {
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
    val type: DonorParentType,
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
