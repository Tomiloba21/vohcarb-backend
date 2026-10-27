package app.vohcarb.game

import app.vohcarb.auth.userId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/games")
@Tag(name = "Games", description = "Daily word-game challenges, globally or in a group, with leaderboards")
@SecurityRequirement(name = "bearer")
class GameController(private val daily: DailyGameService) {

    @GetMapping("/daily")
    @Operation(summary = "Today's challenges (UTC day) and how you did; pass a group for the group's own challenges")
    fun overview(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) group: Long?): DailyOverviewDto =
        daily.overview(jwt.userId, group)

    @PostMapping("/daily/{challenge}/start")
    @Operation(summary = "Start (or resume) a challenge: returns the letters, category or hints, and the deadline")
    fun start(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable challenge: Challenge,
        @RequestParam(required = false) group: Long?,
    ): PlayDto = daily.start(jwt.userId, challenge, group)

    @PostMapping("/plays/{playId}/reveal")
    @Operation(summary = "Complete the word: show one more letter of a word (costs points)")
    fun reveal(@AuthenticationPrincipal jwt: Jwt, @PathVariable playId: Long, @Valid @RequestBody request: RevealRequest): HintDto =
        daily.reveal(jwt.userId, playId, request.index)

    @PostMapping("/plays/{playId}/submit")
    @Operation(summary = "Hand in your answers; returns your result")
    fun submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable playId: Long, @Valid @RequestBody request: SubmitRequest): ResultDto =
        daily.submit(jwt.userId, playId, request)

    @GetMapping("/plays/{playId}")
    @Operation(summary = "Your result for a play, with points per answer (Name-Place-Animal-Thing points change as others play)")
    fun result(@AuthenticationPrincipal jwt: Jwt, @PathVariable playId: Long): ResultDto = daily.result(jwt.userId, playId)

    @GetMapping("/daily/{challenge}/leaderboard")
    @Operation(summary = "Who scored highest: globally (top 50 and you) or in a group")
    fun leaderboard(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable challenge: Challenge,
        @RequestParam(required = false) group: Long?,
        @Parameter(description = "Defaults to today (UTC)")
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): GameLeaderboardDto = daily.leaderboard(jwt.userId, challenge, group, date)

    @GetMapping("/daily/review")
    @Operation(summary = "Group Name-Place-Animal-Thing: today's names and places from others that aren't in our lists")
    fun review(@AuthenticationPrincipal jwt: Jwt, @RequestParam group: Long): List<ReviewItemDto> = daily.review(jwt.userId, group)

    @PutMapping("/answers/{answerId}/vote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Accept or reject another group member's unlisted answer (an answer stands unless most voters reject it)")
    fun vote(@AuthenticationPrincipal jwt: Jwt, @PathVariable answerId: Long, @RequestBody request: VoteRequest) =
        daily.vote(jwt.userId, answerId, request.accepted)
}
