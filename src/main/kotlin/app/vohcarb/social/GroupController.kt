package app.vohcarb.social

import app.vohcarb.auth.userId
import app.vohcarb.catalog.PageResponse
import app.vohcarb.catalog.WordOfTheDayDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/groups")
@Tag(name = "Groups", description = "Friend groups: invites, shared word list, word of the day, leaderboard")
@SecurityRequirement(name = "bearer")
class GroupController(private val groups: GroupService, private val leaderboards: LeaderboardService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a group; you become its owner")
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateGroupRequest): GroupDetailDto =
        groups.create(jwt.userId, request.name)

    @GetMapping
    @Operation(summary = "Groups you're in")
    fun list(@AuthenticationPrincipal jwt: Jwt): List<GroupSummaryDto> = groups.list(jwt.userId)

    @PostMapping("/join")
    @Operation(summary = "Join a group with its invite code")
    fun join(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: JoinGroupRequest): GroupDetailDto =
        groups.join(jwt.userId, request.code)

    @GetMapping("/{groupId}")
    @Operation(summary = "A group with its members and invite code")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: Long): GroupDetailDto = groups.detail(jwt.userId, groupId)

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Owner only: delete the group and its word list")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: Long) = groups.delete(jwt.userId, groupId)

    @PostMapping("/{groupId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Leave a group (an owner hands it to the longest-standing member)")
    fun leave(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: Long) = groups.leave(jwt.userId, groupId)

    @DeleteMapping("/{groupId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Owner only: remove a member")
    fun removeMember(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: Long, @PathVariable memberId: Long) =
        groups.removeMember(jwt.userId, groupId, memberId)

    @PostMapping("/{groupId}/invite-code")
    @Operation(summary = "Owner only: replace the invite code (the old one stops working)")
    fun newInviteCode(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: Long): GroupDetailDto =
        groups.newInviteCode(jwt.userId, groupId)

    @GetMapping("/{groupId}/words")
    @Operation(summary = "The group's shared word list, newest first")
    fun words(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable groupId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): PageResponse<GroupWordDto> = groups.words(jwt.userId, groupId, page, size)

    @PostMapping("/{groupId}/words")
    @Operation(summary = "Add a word to the group's list, with an optional note")
    fun addWord(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable groupId: Long,
        @Valid @RequestBody request: AddGroupWordRequest,
    ): GroupWordDto = groups.addWord(jwt.userId, groupId, request)

    @DeleteMapping("/{groupId}/words/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a word from the list (whoever added it, or the owner)")
    fun removeWord(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: Long, @PathVariable slug: String) =
        groups.removeWord(jwt.userId, groupId, slug)

    @GetMapping("/{groupId}/word-of-the-day")
    @Operation(summary = "Today's word for this group (UTC day), the same for every member")
    fun wordOfTheDay(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: Long): WordOfTheDayDto =
        groups.wordOfTheDay(jwt.userId, groupId)

    @GetMapping("/{groupId}/leaderboard")
    @Operation(summary = "Members ranked by answers in the last N days (default 7), then by streak")
    fun leaderboard(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable groupId: Long,
        @RequestParam(defaultValue = "7") @Min(1) @Max(30) days: Int,
    ): LeaderboardDto = leaderboards.leaderboard(jwt.userId, groupId, days)
}
