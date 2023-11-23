package ju.ma.app

import ju.ma.app.services.FilterService
import org.hibernate.envers.RevisionListener
import org.springframework.stereotype.Component

@Component
class CustomRevisionEntityListener(
    private var filterService: FilterService
) : RevisionListener {

    /*
    todo Change this with better logic
     */
    override fun newRevision(revisionEntity: Any?) {
        val customRevisionInfo: CustomRevisionInfo = revisionEntity as CustomRevisionInfo
        customRevisionInfo.customUser = filterService.currentUser?.name.toString()
    }

}
