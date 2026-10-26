package app.vohcarb.auth

import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties("vohcarb.auth")
@Validated
data class AuthProperties(
    /** HMAC key for signing our access tokens. Set JWT_SECRET in production; the dev profile has a throwaway one. */
    @field:Size(min = 32, message = "must be at least 32 characters (set JWT_SECRET)")
    val jwtSecret: String = "",
    val tokenTtl: Duration = Duration.ofDays(30),
    val issuer: String = "vohcarb",
    /** Enables POST /api/v1/auth/dev-login, which signs anyone in by email. Never enable outside local development. */
    val devLoginEnabled: Boolean = false,
)
