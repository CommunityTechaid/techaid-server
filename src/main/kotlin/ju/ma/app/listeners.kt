package ju.ma.app

import org.hibernate.envers.RevisionListener
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken


class CustomRevisionEntityListener : RevisionListener {

    /* todo Use the same method from FilterService maybe?
     I do not know how to autowire the service to an event listener
     ~ Akhil M
     */
    val currentUser: JwtAuthenticationToken?
        get() {
            val auth = SecurityContextHolder.getContext().authentication ?: return null
            return if (auth is JwtAuthenticationToken) {
                auth
            } else null
        }

    /*
    todo Change this with better logic
     */
    override fun newRevision(revisionEntity: Any?) {
        val customRevisionInfo: CustomRevisionInfo = revisionEntity as CustomRevisionInfo
        customRevisionInfo.customUser = currentUser?.let { user ->
            return@let user.tokenAttributes["https://lambeth-techaid.ju.ma/name"]?.toString() ?: ""
        }?.trim() ?: "null"
    }

}
