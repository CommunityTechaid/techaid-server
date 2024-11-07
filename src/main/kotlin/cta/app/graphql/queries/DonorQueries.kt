package cta.app.graphql.queries

import cta.app.Donor
import cta.app.DonorRepository
import cta.app.Volunteer
import cta.app.graphql.filters.DonorWhereInput
import cta.app.services.FilterService
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
@PreAuthorize("hasAnyAuthority('app:admin', 'read:donors')")
class DonorQueries(
    private val donors: DonorRepository,
    private val filterService: FilterService
)  {
    @QueryMapping
    fun donorsConnection(@Argument page: PaginationInput?, @Argument where: DonorWhereInput?): Page<Donor> {
        val filter = filterService.donorFilter()
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return donors.findAll(filter, f.create())
        }
        return donors.findAll(filter.and(where.build()), f.create())
    }

    @QueryMapping
    fun donors(@Argument where: DonorWhereInput, @Argument orderBy: MutableList<KeyValuePair>?): List<Donor> {
        val filter = filterService.donorFilter()
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            donors.findAll(filter.and(where.build()), sort).toList()
        } else {
            donors.findAll(filter.and(where.build())).toList()
        }
    }

    @QueryMapping
    fun donor(@Argument where: DonorWhereInput): Optional<Donor> =
        donors.findOne(filterService.donorFilter().and(where.build()))
}

@Controller
class DonorResolver {
    companion object {
        val EMAIL_MASK = Regex("(?<=.)[^@](?=[^@]*[^@]@)|(?:(?<=@.)|(?!^)\\G(?=[^@]*$)).(?!$)")
    }

    fun email(entity: Volunteer): String {
        return entity.email
    }

    @QueryMapping
    fun phoneNumber(@Argument entity: Volunteer): String {
        return entity.phoneNumber
    }
}
