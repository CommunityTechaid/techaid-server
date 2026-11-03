package cta.app.services

import com.querydsl.core.BooleanBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service

@Service
class FilterService {
    @Value("\${auth0.token-attribute}")
    private lateinit var tokenAttribute: String

    val currentUser: JwtAuthenticationToken?
        get() {
            val auth = SecurityContextHolder.getContext().authentication ?: return null
            return if (auth is JwtAuthenticationToken) {
                auth
            } else {
                null
            }
        }

    fun authenticated(): Boolean {
        val user = currentUser ?: return false
        return user.authorities.isNotEmpty()
    }

    fun userDetails(): OAuthUser {
        currentUser?.let { user ->
            val name = user.tokenAttributes["$tokenAttribute/name"]?.toString() ?: ""
            val email = user.tokenAttributes["$tokenAttribute/email"]?.toString() ?: ""
            return OAuthUser(name.trim(), email.trim())
        }
        return OAuthUser("", "")
    }

    // Both return an empty filter: no row-level restriction applies to kits or donors. They used
    // to check `admin:kits` / `admin:donors` first, but every branch returned the same empty
    // filter, and `admin:donors` is not even defined in Auth0. Kept as seams for the callers.
    fun kitFilter(): BooleanBuilder = BooleanBuilder()

    fun donorFilter(): BooleanBuilder = BooleanBuilder()

    fun hasAuthority(permission: String): Boolean {
        val user = currentUser ?: return false
        return user.authorities.any { it.authority == permission }
    }
}

data class OAuthUser(
    val name: String,
    val email: String,
) {
    val empty: Boolean by lazy {
        name.trim().isBlank() && email.trim().isBlank()
    }

    val identifier by lazy {
        if (name.trim().isBlank()) {
            email
        } else if (name != email && email.trim().isNotBlank()) {
            "$name<$email>"
        } else {
            ""
        }
    }
}
