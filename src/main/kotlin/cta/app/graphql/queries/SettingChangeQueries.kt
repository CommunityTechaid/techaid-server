package cta.app.graphql.queries

import com.querydsl.core.BooleanBuilder
import cta.app.AdminSettingChange
import cta.app.AdminSettingChangeRepository
import cta.app.QAdminSettingChange
import cta.app.SettingType
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller

@Controller
class SettingChangeQueries(
    private val history: AdminSettingChangeRepository,
) {
    /** Admin-panel setting history (#327), newest first. Same authority that gates every write to these settings. */
    @PreAuthorize("hasAnyAuthority('app:admin')")
    @QueryMapping
    fun settingChanges(
        @Argument settingType: String?,
        @Argument settingKey: String?,
        @Argument page: Int,
        @Argument size: Int,
    ): Page<AdminSettingChange> {
        val pageable =
            PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 200), Sort.by(Sort.Order.desc("changedAt"), Sort.Order.desc("id")))
        val entry = QAdminSettingChange.adminSettingChange
        val filter = BooleanBuilder()
        if (settingType != null) {
            val type = SettingType.entries.firstOrNull { it.name == settingType } ?: return Page.empty(pageable)
            filter.and(entry.settingType.eq(type))
        }
        if (settingKey != null) filter.and(entry.settingKey.eq(settingKey))
        return history.findAll(filter, pageable)
    }
}
