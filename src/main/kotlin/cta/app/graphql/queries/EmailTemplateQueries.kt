package cta.app.graphql.queries

import cta.app.EmailTemplate
import cta.app.EmailTemplateRepository
import cta.app.graphql.filters.EmailTemplateWhereInput
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.Optional

@Controller
@PreAuthorize("hasAnyAuthority('app:admin', 'read:emails')")
class EmailTemplateQueries(
    private val templates: EmailTemplateRepository
)  {

    @QueryMapping("emailTemplatesConnection")
    fun emailTemplatesConnection(
        @Argument page: PaginationInput?,
        @Argument where: EmailTemplateWhereInput?
    ): Page<EmailTemplate> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return templates.findAll(f.create())
        }

        return templates.findAll(where.build(), f.create())
    }

    @QueryMapping("emailTemplates")
    fun emailTemplates(
        @Argument where: EmailTemplateWhereInput,
        @Argument orderBy: MutableList<KeyValuePair>?
    ): List<EmailTemplate> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            templates.findAll(where.build(), sort).toList()
        } else {
            templates.findAll(where.build()).toList()
        }
    }

    @QueryMapping("emailTemplate")
    fun emailTemplate(@Argument where: EmailTemplateWhereInput): Optional<EmailTemplate> =
        templates.findOne(where.build())
}
