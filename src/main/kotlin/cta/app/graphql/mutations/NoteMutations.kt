package cta.app.graphql.mutations

import cta.app.KitRepository
import cta.app.Note
import cta.app.NoteRepository
import cta.app.services.FilterService
import cta.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.constraints.NotNull
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:kits')")
@Transactional
class NoteMutations(
    private val filterService: FilterService,
    private val notes: NoteRepository,
    private val kits: KitRepository
) {

    /* The creation of the note is handled by the updateKit mutation. This method is being left here in case it is needed in future.
    * It is commented to avoid confusion while reading the code because I was confused.
    fun createNote(@Valid data: CreateNoteInput): Note {

        val userEmail = filterService.userDetails().email
        val kit = kits.findOne(filterService.kitFilter().and(QKit.kit.id.eq(data.kitId))).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a kit with id: ${data.kitId}")

        val note = Note(
            content = data.content,
            kit = kit,
            volunteer = userEmail
        )

        return notes.save(note)
    }*/

    /* Updating a note is not a feature that is being provided. Leaving the method commented here in case it is needed in the future.
    fun updateNote(@Valid data: UpdateNoteInput): Note {

        val userEmail = filterService.userDetails().email
        val note: Note = notes.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a note with id: ${data.id}")

        if (userEmail == note.volunteer){
            return notes.save(data.apply(note).apply {
                volunteer = userEmail
            })
        } else {
            throw Exception("You cannot edit other user's notes")
        }
    }*/

    @MutationMapping
    fun deleteNote(id: Long): Boolean {

        val volunteer = filterService.userDetails().name.ifBlank {
            filterService.userDetails().email
        }
        val note: Note = notes.findById(id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a note with id: $id")
        if (volunteer == note.volunteer){
            notes.delete(note)
        } else {
            throw IllegalArgumentException("You cannot delete other user's notes")
        }
        return true
    }
}

data class CreateNoteInput(
    val content: String = "",
    val kitId: Long
) {}

data class UpdateNoteInput(
    @get:NotNull
    val id: Long,
    val content: String = ""
){

    fun apply(entity: Note): Note {
        val self = this
        return entity.apply {
            content = self.content
        }
    }

}