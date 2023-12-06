package ju.ma.app.graphql.mutations

import com.coxautodev.graphql.tools.GraphQLMutationResolver
import ju.ma.app.KitRepository
import ju.ma.app.Note
import ju.ma.app.NoteRepository
import ju.ma.app.QKit
import ju.ma.app.services.FilterService
import ju.ma.toNullable
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import javax.persistence.EntityNotFoundException
import javax.validation.Valid
import javax.validation.constraints.NotNull

@Component
@Validated
@PreAuthorize("hasAnyAuthority('write:kits')")
@Transactional
class NoteMutations(
    private val filterService: FilterService,
    private val notes: NoteRepository,
    private val kits: KitRepository
) : GraphQLMutationResolver {

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
    }

    fun updateNote(@Valid data: UpdateNoteInput): Note {

        val userEmail = filterService.userDetails().email
        val note: Note = notes.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a note with id: ${data.id}")

        if (userEmail == note.volunteer){
            notes.delete(note)
        } else {
            throw Exception("You cannot edit other user's notes")
        }
        return notes.save(data.apply(note).apply {
            volunteer = userEmail
        })
    }

    fun deleteNote(id: Long): Boolean {

        val userEmail = filterService.userDetails().email
        val note: Note = notes.findById(id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a note with id: $id")
        if (userEmail == note.volunteer){
            notes.delete(note)
        } else {
            throw Exception("You cannot delete other user's notes")
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