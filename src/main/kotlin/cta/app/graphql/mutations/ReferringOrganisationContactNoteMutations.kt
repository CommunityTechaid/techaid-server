package cta.app.graphql.mutations


import cta.app.ReferringOrganisationContactNote
import cta.app.ReferringOrganisationContactNoteRepository
import cta.app.services.FilterService
import cta.toNullable
import jakarta.persistence.EntityNotFoundException
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:organisations')")
@Transactional
class ReferringOrganisationContactNoteMutations(
    private val filterService: FilterService,
    private val referringOrganisationContactNotes: ReferringOrganisationContactNoteRepository
) {

    @MutationMapping
    fun deleteReferringOrganisationContactNote(@Argument id: Long): Boolean {
        val volunteer = filterService.userDetails().name.ifBlank {
            filterService.userDetails().email
        }
        val referringOrganisationContactNote: ReferringOrganisationContactNote = referringOrganisationContactNotes.findById(id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a note with id: $id")
        if (volunteer == referringOrganisationContactNote.volunteer){
            referringOrganisationContactNotes.delete(referringOrganisationContactNote)
        } else {
            throw IllegalArgumentException("You cannot delete other user's notes")
        }
        return true
    }

}

data class ReferringOrganisationContactNoteInput(
    val content: String
) {
}