package app.vohcarb.auth

import app.vohcarb.user.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DevLoginRequest(
    @field:NotBlank @field:Email @field:Size(max = 255) val email: String,
    @field:Size(max = 128) val displayName: String? = null,
)

/**
 * Signs anyone in by email alone, so the app can be built and tested before Google Sign-In exists.
 * Only registered when vohcarb.auth.dev-login-enabled=true (the dev and test profiles).
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnBooleanProperty("vohcarb.auth.dev-login-enabled")
@Tag(name = "Auth", description = "Sign in and get an access token")
class DevAuthController(private val users: UserService, private val tokens: TokenService) {

    @PostMapping("/dev-login")
    @Operation(summary = "DEV ONLY: sign in as any email address")
    fun devLogin(@Valid @RequestBody request: DevLoginRequest): TokenResponse {
        val email = request.email.trim().lowercase()
        val name = request.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: email.substringBefore('@')
        return tokens.issue(users.signIn(provider = "dev", subject = email, email = email, displayName = name))
    }
}
