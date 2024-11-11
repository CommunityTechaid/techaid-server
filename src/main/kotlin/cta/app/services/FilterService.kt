package cta.app.services

import com.querydsl.core.BooleanBuilder
import com.querydsl.jpa.JPAExpressions
import cta.app.QDonor
import cta.app.QKit
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
            } else null
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

    fun kitFilter(): BooleanBuilder {
        val filter = BooleanBuilder()
        val user = currentUser ?: return filter
        if (hasAuthority("admin:kits")) {
            return filter
        }
        return filter
    }

    fun donorFilter(): BooleanBuilder {
        val filter = BooleanBuilder()
        val user = currentUser ?: return filter
        if (hasAuthority("admin:donors")) {
            return filter
        }
        return filter
    }

    fun hasAuthority(permission: String): Boolean {
        val user = currentUser ?: return false
        return user.authorities.any { it.authority == permission }
    }
}

data class OAuthUser(val name: String, val email: String) {
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
