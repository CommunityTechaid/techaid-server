package cta.app.graphql.queries

import cta.app.Kit
import cta.app.KitAttributes
import cta.app.KitRepository
import cta.app.KitStatusCount
import cta.app.KitTypeCount
import cta.app.graphql.filters.KitWhereInput
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
@PreAuthorize("hasAnyAuthority('read:kits')")
class KitQueries(
    private val kits: KitRepository,
    private val filterService: FilterService
)  {


    @QueryMapping
    fun statusCount(): List<KitStatusCount> {
        return kits.statusCount()
    }

    @QueryMapping
    fun typeCount(): List<KitTypeCount> {
        return kits.typeCount()
    }

    @QueryMapping
    fun kitsConnection(@Argument page: PaginationInput?, @Argument where: KitWhereInput?): Page<Kit> {
        val f: PaginationInput = page ?: PaginationInput()
        val filter = filterService.kitFilter()
        if (where == null) {
            return kits.findAll(filter, f.create())
        }
        return kits.findAll(filter.and(where.build()), f.create())
    }

    @QueryMapping
    fun kits(@Argument where: KitWhereInput, @Argument orderBy: MutableList<KeyValuePair>?): List<Kit> {
        val filter = filterService.kitFilter()
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            kits.findAll(filter.and(where.build()), sort).toList()
        } else {
            kits.findAll(filter.and(where.build())).toList()
        }
    }

    @QueryMapping
    fun kit(@Argument where: KitWhereInput): Optional<Kit> = kits.findOne(filterService.kitFilter().and(where.build()))
}

@Controller
class kitResolver {
    @QueryMapping
    fun getAttributes(@Argument kit: Kit): KitAttributes {
        val attr = kit.attributes
        attr.kit = kit
        return attr
    }
}
