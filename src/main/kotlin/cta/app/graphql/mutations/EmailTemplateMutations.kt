package cta.app.graphql.mutations

import jakarta.persistence.EntityNotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import cta.app.EmailTemplate
import cta.app.EmailTemplateRepository
import cta.toNullable
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:emails')")
@Transactional
class EmailTemplateMutations(
    private val emails: EmailTemplateRepository
){

    @MutationMapping
    fun createEmailTemplate(@Valid data: CreateEmailTemplateInput): EmailTemplate {
        return emails.save(data.entity)
    }

    @MutationMapping
    fun updateEmailTemplate(@Valid data: UpdateEmailTemplateInput): EmailTemplate {
        val entity = emails.findById(data.id).toNullable()
            ?: throw EntityNotFoundException("Unable to locate a template with id: ${data.id}")
        return data.apply(entity)
    }

    @PreAuthorize("hasAnyAuthority('delete:emails')")
    @MutationMapping
    fun deleteEmailTemplate(id: Long): Boolean {
        val entity =
            emails.findById(id).toNullable() ?: throw EntityNotFoundException("No template with id: $id")
        emails.delete(entity)
        return true
    }
}

data class CreateEmailTemplateInput(
    val body: String,
    val subject: String,
    val active: Boolean
) {
    val entity by lazy {
        EmailTemplate(
            active = active,
            body = body,
            subject = subject
        )
    }
}

data class UpdateEmailTemplateInput(
    @get:NotNull
    val id: Long,
    val body: String,
    val subject: String,
    val active: Boolean
) {
    fun apply(entity: EmailTemplate): EmailTemplate {
        val self = this
        return entity.apply {
            body = self.body
            subject = self.subject
            active = self.active
        }
    }
}
