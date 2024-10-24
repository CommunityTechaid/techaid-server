package cta.app.graphql.queries

import cta.app.Volunteer
import cta.app.VolunteerRepository
import cta.app.graphql.filters.VolunteerWhereInput
import cta.app.services.FilterService
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import java.util.Optional

@Controller
@PreAuthorize("hasAnyAuthority('read:volunteers', 'read:volunteers:assigned')")
class VolunteerQueries(
    private val volunteers: VolunteerRepository,
    private val filterService: FilterService
)  {
    @QueryMapping
    fun volunteersConnection(page: PaginationInput?, where: VolunteerWhereInput?): Page<Volunteer> {
        val filter = filterService.volunteerFilter()
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return volunteers.findAll(filter, f.create())
        }
        return volunteers.findAll(filter.and(where.build()), f.create())
    }

    @QueryMapping
    fun volunteers(where: VolunteerWhereInput, orderBy: MutableList<KeyValuePair>?): List<Volunteer> {
        val filter = filterService.volunteerFilter()
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            volunteers.findAll(filter.and(where.build()), sort).toList()
        } else {
            volunteers.findAll(filter.and(where.build())).toList()
        }
    }

    @QueryMapping
    fun volunteer(where: VolunteerWhereInput): Optional<Volunteer> =
        volunteers.findOne(filterService.volunteerFilter().and(where.build()))
}

@Controller
class VolunteerResolver {
    companion object {
        val EMAIL_MASK = Regex("(?<=.)[^@](?=[^@]*[^@]@)|(?:(?<=@.)|(?!^)\\G(?=[^@]*$)).(?!$)")
    }

    @QueryMapping
    fun email(entity: Volunteer): String {
        return entity.email
    }

    @QueryMapping
    fun phoneNumber(entity: Volunteer): String {
        return entity.phoneNumber
    }
}
