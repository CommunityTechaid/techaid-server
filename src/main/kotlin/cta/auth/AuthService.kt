package cta.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class AuthService {
    @Value("\${auth.admin-header:X-Auth-Admin-Secret}")
    lateinit var adminHeader: String

    @Value("\${auth.admin-secret}")
    private lateinit var secret: String

    fun adminForToken(token: String): AppUser? {
        if (token.isBlank() || secret.isBlank()) {
            return null
        }

        // Constant-time comparison: this token grants full read/write/delete, so it must
        // not be guessable via response-timing differences.
        if (MessageDigest.isEqual(token.toByteArray(), secret.toByteArray())) {
            return AppUser(
                user =
                    User(
                        name = "Admin Token",
                        username = "root",
                    ),
                password = token,
                app = "*",
                authorities =
                    listOf(
                        "read:kits",
                        "read:donors",
                        "read:users",
                        "write:kits",
                        "write:donors",
                        "write:users",
                        "delete:kits",
                        "delete:donors",
                        "delete:users",
                        "read:organisations",
                        "write:organisations",
                        "delete:organisations",
                    ).map { SimpleGrantedAuthority("$it") }
                        .toMutableList(),
            )
        }

        return null
    }
}
