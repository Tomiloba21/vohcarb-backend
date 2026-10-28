package app.vohcarb.game.live

import app.vohcarb.auth.userId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/games/live")
@Tag(name = "Live games", description = "Rooms for playing friends or strangers in real time; the match runs over /ws/games/rooms/{id}")
@SecurityRequirement(name = "bearer")
class LiveController(private val rooms: LiveRooms) {

    @GetMapping("/rooms")
    @Operation(summary = "Open public rooms, rooms made for your groups, and rooms you're in")
    fun lobby(@AuthenticationPrincipal jwt: Jwt): LobbyDto = rooms.lobby(jwt.userId)

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a room (FRIENDS: join by code or from its group; PUBLIC: listed in the lobby). You're the host")
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: CreateRoomRequest): RoomSummaryDto = rooms.create(jwt.userId, request)

    @PostMapping("/rooms/join")
    @Operation(summary = "Join a room with its 6-character code")
    fun joinByCode(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: JoinByCodeRequest): RoomSummaryDto =
        rooms.joinByCode(jwt.userId, request.code)

    @PostMapping("/rooms/{roomId}/join")
    @Operation(summary = "Join a public room, or one made for your group")
    fun join(@AuthenticationPrincipal jwt: Jwt, @PathVariable roomId: String): RoomSummaryDto = rooms.join(jwt.userId, roomId)

    @PostMapping("/quick")
    @Operation(summary = "Quick match: join others waiting for the same game; starts by itself once 2-6 players are in")
    fun quick(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: QuickMatchRequest): RoomSummaryDto = rooms.quick(jwt.userId, request.challenge)

    @PostMapping("/rooms/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Leave a room (during a match your score stays, but you're out)")
    fun leave(@AuthenticationPrincipal jwt: Jwt, @PathVariable roomId: String) = rooms.leave(jwt.userId, roomId)
}
