package cta.app.graphql.mutations


import cta.app.ReferringOrganisationNote
import cta.app.ReferringOrganisationNoteRepository
import cta.app.services.FilterService
import cta.toNullable
import jakarta.persistence.EntityNotFoundException
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:organisations')")
@Transactional
class ReferringOrganisationNoteMutations(
    private val filterService: FilterService,
    private val referringOrganisationNotes: ReferringOrganisationNoteRepository
) {

    @MutationMapping
    fun deleteReferringOrganisationNote(id: Long): Boolean{
        val volunteer = filterService.userDetails().name.ifBlank {
            filterService.userDetails().email
        }
        val referringOrganisationNote: ReferringOrganisationNote = referringOrganisationNotes.findById(id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a note with id: $id")
        if (volunteer == referringOrganisationNote.volunteer){
            referringOrganisationNotes.delete(referringOrganisationNote)
        } else {
            throw IllegalArgumentException("You cannot delete other user's notes")
        }
        return true
    }

}

data class ReferringOrganisationNoteInput(
    val content: String
) {
}