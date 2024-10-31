package cta.app.graphql.mutations

import cta.app.DeviceRequestNote
import cta.app.DeviceRequestNoteRepository
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
class DeviceRequestNoteMutations(
    private val filterService: FilterService,
    private val deviceRequestNotes: DeviceRequestNoteRepository
) {

    @MutationMapping
    fun deleteDeviceRequestNote(id: Long): Boolean{
        val volunteer = filterService.userDetails().name.ifBlank {
            filterService.userDetails().email
        }
        val deviceRequestNote: DeviceRequestNote = deviceRequestNotes.findById(id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a note with id: $id")
        if (volunteer == deviceRequestNote.volunteer){
            deviceRequestNotes.delete(deviceRequestNote)
        } else {
            throw IllegalArgumentException("You cannot delete other user's notes")
        }
        return true
    }

}

data class DeviceRequestNoteInput(
    val content: String
) {
}