package app.vohcarb.user

import app.vohcarb.auth.userId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "The signed-in user")
@SecurityRequirement(name = "bearer")
class MeController(private val users: UserService) {

    @GetMapping
    @Operation(summary = "The signed-in user's profile")
    fun me(@AuthenticationPrincipal jwt: Jwt): MeDto = users.me(jwt.userId)

    @GetMapping("/preferences")
    @Operation(summary = "Feed preferences: level range, categories, words per day, reminders")
    fun preferences(@AuthenticationPrincipal jwt: Jwt): PreferencesDto = users.preferences(jwt.userId)

    @PutMapping("/preferences")
    @Operation(summary = "Replace feed preferences")
    fun updatePreferences(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: UpdatePreferencesRequest,
    ): PreferencesDto = users.updatePreferences(jwt.userId, request)
}
