package cta.app.graphql.queries

import com.coxautodev.graphql.tools.GraphQLQueryResolver
import cta.app.DeviceRequest
import cta.app.DeviceRequestRepository
import cta.app.graphql.filters.DeviceRequestWhereInput
import cta.app.services.FilterService
import cta.graphql.KeyValuePair
import cta.graphql.PaginationInput
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import java.util.Optional

@Component
class DeviceRequestQueries(
    private val deviceRequests: DeviceRequestRepository,
    private val filterService: FilterService
) : GraphQLQueryResolver {

    fun requestCount(): RequestCount? {
        if (filterService.authenticated()) {
            return deviceRequests.requestCount()
        }
        return null
    }

    /*  @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
      fun organisationsConnection(page: PaginationInput?, where: DeviceRequestWhereInput?): Page<DeviceRequest> {
          val f: PaginationInput = page ?: PaginationInput()
          if (where == null) {
              return deviceRequests.findAll(f.create())
          }
          return deviceRequests.findAll(where.build(), f.create())
      }
  */
    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun deviceRequests(where: DeviceRequestWhereInput, orderBy: MutableList<KeyValuePair>?): List<DeviceRequest> {
        return if (orderBy != null) {
            val sort: Sort = Sort.by(orderBy.map { Sort.Order(Sort.Direction.fromString(it.value), it.key) })
            deviceRequests.findAll(where.build(), sort).toList()
        } else {
            deviceRequests.findAll(where.build()).toList()
        }
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun deviceRequestConnection(page: PaginationInput?, where: DeviceRequestWhereInput?): Page<DeviceRequest> {
        val f: PaginationInput = page ?: PaginationInput()
        if (where == null) {
            return deviceRequests.findAll(f.create())
        }
        return deviceRequests.findAll(where.build(), f.create())
    }

    @PreAuthorize("hasAnyAuthority('app:admin', 'read:organisations')")
    fun deviceRequest(where: DeviceRequestWhereInput): Optional<DeviceRequest> = deviceRequests.findOne(where.build())
}