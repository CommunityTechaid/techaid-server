package cta.app.graphql.mutations

import cta.app.ReferringOrganisationContact
import cta.app.ReferringOrganisationContactNote
import cta.app.ReferringOrganisationContactRepository
import cta.app.ReferringOrganisationRepository
import cta.app.services.FilterService
import cta.toNullable
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@Transactional
class ReferringOrganisationContactMutations(
    private val referringOrganisationContacts: ReferringOrganisationContactRepository,
    private val referringOrganisations: ReferringOrganisationRepository,
    private val filterService: FilterService,
) {
    // Staff only. The public referral form sends an unknown referee or organisation to a Typeform,
    // so anonymous creation is no longer offered.
    @PreAuthorize("hasAnyAuthority('app:admin', 'write:organisations', 'write:referees')")
    @MutationMapping
    fun createReferringOrganisationContact(
        @Argument @Valid data: CreateReferringOrganisationContactInput,
    ): ReferringOrganisationContact {
        val referringOrganisation =
            referringOrganisations.findById(data.referringOrganisation).toNullable()
                ?: throw EntityNotFoundException("No referring organisation was found with id {$data.referringOrganisation}")

        /**
         * We add another level of check here to verify that a user with the same full name and email does not already exist.
         * We do this to ensure that no duplicate users have the same full name and email ID due to errors in the frontend
         */
        var referringOrganisationContact: ReferringOrganisationContact?
        try {
            referringOrganisationContact =
                referringOrganisationContacts.findOneByFullNameAndEmailAndReferringOrganisation(
                    data.fullName,
                    data.email,
                    referringOrganisation,
                )
            if (referringOrganisationContact != null) {
                return referringOrganisationContact
            }
        } catch (exception: Exception) {
            throw RuntimeException("There was an error when saving the details of user " + data.fullName + ". Please contact CTA.")
        }

        referringOrganisationContact =
            ReferringOrganisationContact(
                fullName = data.fullName,
                address = data.address ?: "",
                email = data.email,
                phoneNumber = data.phoneNumber,
                referringOrganisation = referringOrganisation,
                isMembershipContact = data.isMembershipContact ?: false,
            )

        return referringOrganisationContacts.save(referringOrganisationContact)
    }

    @PreAuthorize("hasAnyAuthority('write:organisations', 'write:referees')")
    @MutationMapping
    fun updateReferringOrganisationContact(
        @Argument @Valid data: UpdateReferringOrganisationContactInput,
    ): ReferringOrganisationContact {
        val entity =
            referringOrganisationContacts.findById(data.id).toNullable()
                ?: throw EntityNotFoundException("Unable to locate a organisation contact with id: ${data.id}")

        if (entity.fullName == ReferringOrganisationContact.GDPR_ERASED_NAME) {
            // An erased contact stores email '' and the detail form sends it back, so the email
            // rule below must not run first. Only `archived` may change: the cleanup never scrubs
            // an erased row again, so any other edit could add personal data that is kept forever.
            if (!data.changesOnlyArchived(entity)) throw RefereeErasedException()
        } else if (data.email.isBlank()) {
            // Was @NotBlank on the input; moved here so it can skip erased contacts. Same exception
            // type and message as the annotation produced, so live contacts behave exactly as before.
            throw ConstraintViolationException("updateReferringOrganisationContact.data.email: must not be blank", emptySet())
        }

        return data.apply(entity).apply {
            if (entity.referringOrganisation.id != data.referringOrganisationId) {
                val referringOrganisation =
                    referringOrganisations.findById(data.referringOrganisationId).toNullable()
                        ?: throw EntityNotFoundException("No referring organisation was found with id {$data.referringOrganisation}")

                referringOrganisation.addContact(this)
            }

            if (data.note != null) {
                if (data.note.content !== "") {
                    val volunteer =
                        filterService.userDetails().name.ifBlank {
                            filterService.userDetails().email
                        }
                    val note =
                        ReferringOrganisationContactNote(
                            content = data.note.content,
                            referringOrganisationContact = this,
                            volunteer = volunteer,
                        )
                    referringOrganisationContactNotes.add(note)
                }
            }
        }
    }

    @PreAuthorize("hasAnyAuthority('delete:organisations', 'delete:referees')")
    @MutationMapping
    fun deleteReferringOrganisationContact(
        @Argument id: Long,
    ): Boolean {
        val entity =
            referringOrganisationContacts.findById(id).toNullable()
                ?: throw EntityNotFoundException("No referring organisation contact with id: $id")
        referringOrganisationContacts.delete(entity)
        return true
    }

    // Deliberate rejection: a stable code so the dashboard need not match wording, and the message
    // is written for staff (it reaches the toast verbatim).
    @GraphQlExceptionHandler
    fun handleRefereeErased(ex: RefereeErasedException): GraphQLError =
        GraphqlErrorBuilder
            .newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.message)
            .extensions(mapOf("code" to RefereeErasedException.CODE))
            .build()
}

/** An edit other than archiving was attempted on a GDPR-erased referee contact. */
class RefereeErasedException :
    RuntimeException(
        "This referee was erased under the GDPR retention policy and can no longer be edited. It can only be archived.",
    ) {
    companion object {
        const val CODE = "REFEREE_ERASED"
    }
}

data class CreateReferringOrganisationContactInput(
    @get:NotBlank
    var fullName: String,
    var address: String? = null,
    @get:NotBlank
    @get:Email
    var email: String = "",
    var phoneNumber: String,
    @get:NotNull
    var referringOrganisation: Long,
    // Optional; absent means false.
    var isMembershipContact: Boolean? = null,
)

data class UpdateReferringOrganisationContactInput(
    @get:NotNull
    val id: Long,
    @get:NotBlank
    var fullName: String,
    var address: String? = null,
    // Not @NotBlank: the resolver requires it for every contact except a GDPR-erased one, whose
    // stored email is '' and which may still be archived. @Email still rejects a malformed value.
    @get:Email
    var email: String = "",
    var phoneNumber: String,
    @get:NotNull
    var referringOrganisationId: Long,
    val archived: Boolean? = null,
    val note: ReferringOrganisationContactNoteInput? = null,
    // Null or absent = unchanged (the referee detail form predates membership).
    val isMembershipContact: Boolean? = null,
) {
    /**
     * True when applying this input would change nothing on [entity] except `archived`: every
     * value apply() writes equals what is stored, the organisation and membership flag are
     * unchanged, and no note text is added.
     */
    fun changesOnlyArchived(entity: ReferringOrganisationContact): Boolean =
        fullName == entity.fullName &&
            (address ?: "") == entity.address &&
            email == entity.email &&
            phoneNumber == entity.phoneNumber &&
            referringOrganisationId == entity.referringOrganisation.id &&
            (isMembershipContact == null || isMembershipContact == entity.isMembershipContact) &&
            note?.content.isNullOrBlank()

    fun apply(entity: ReferringOrganisationContact): ReferringOrganisationContact {
        val self = this
        return entity.apply {
            fullName = self.fullName
            address = self.address ?: ""
            email = self.email
            phoneNumber = self.phoneNumber
            archived = self.archived ?: archived
            isMembershipContact = self.isMembershipContact ?: isMembershipContact
        }
    }
}
