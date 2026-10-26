package app.vohcarb.notify

import app.vohcarb.auth.userId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class RegisterDeviceRequest(
    /** The FCM registration token from the app. */
    @field:NotBlank @field:Size(max = 512) val token: String,
    val platform: Platform,
)

@RestController
@RequestMapping("/api/v1/me/devices")
@Tag(name = "Notifications", description = "Register phones for reminders")
@SecurityRequirement(name = "bearer")
class DeviceController(private val notifications: NotificationService) {

    @PostMapping
    @Operation(summary = "Register this phone's FCM token (call on every app start and when the token changes)")
    fun register(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: RegisterDeviceRequest): DeviceDto =
        notifications.register(jwt.userId, request.token, request.platform)

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Stop notifications to a phone (e.g. on sign-out)")
    fun unregister(@AuthenticationPrincipal jwt: Jwt, @RequestParam token: String) = notifications.unregister(jwt.userId, token)

    @GetMapping
    @Operation(summary = "Your registered phones (only the end of each token is shown)")
    fun list(@AuthenticationPrincipal jwt: Jwt): List<DeviceDto> = notifications.list(jwt.userId)

    @PostMapping("/test")
    @Operation(summary = "Send yourself a test notification")
    fun test(@AuthenticationPrincipal jwt: Jwt): TestPushResult =
        notifications.send(jwt.userId, PushMessage("Vohcarb test", "Notifications work on this phone.", mapOf("type" to "test")))
}
