package cta.app

import cta.app.services.FilterService
import org.hibernate.envers.RevisionListener
import org.springframework.stereotype.Component

@Component
class CustomRevisionEntityListener(
    private var filterService: FilterService
) : RevisionListener {

    override fun newRevision(revisionEntity: Any?) {
        val customRevisionInfo: CustomRevisionInfo = revisionEntity as CustomRevisionInfo
        customRevisionInfo.customUser = filterService.userDetails().email
    }

}
