package app.vohcarb.progress

import app.vohcarb.auth.userId
import app.vohcarb.catalog.PageResponse
import app.vohcarb.catalog.WordDetailDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Progress", description = "Your feed, saved/liked/known words and offline sync")
@SecurityRequirement(name = "bearer")
class ProgressController(private val progress: ProgressService, private val feed: FeedService) {

    @GetMapping("/feed")
    @Operation(summary = "Random unseen words matching your preferences (defaults to your words-per-day)")
    fun feed(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) @Min(1) @Max(50) size: Int?,
    ): List<WordDetailDto> = feed.feed(jwt.userId, size)

    @GetMapping("/words")
    @Operation(summary = "Your saved, liked, known or seen words, most recent first")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "saved, liked, known or seen") @RequestParam(defaultValue = "saved") list: String,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): PageResponse<MyWordDto> {
        val which = WordList.entries.firstOrNull { it.name.equals(list, ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "list must be one of saved, liked, known, seen")
        return progress.list(jwt.userId, which, page, size)
    }

    @GetMapping("/words/{slug}")
    @Operation(summary = "Your state for one word")
    fun state(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String): MyWordDto = progress.state(jwt.userId, slug)

    @PatchMapping("/words/{slug}")
    @Operation(summary = "Set some flags on a word while online, e.g. {\"saved\": true}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable slug: String,
        @RequestBody patch: WordStatePatch,
    ): MyWordDto = progress.update(jwt.userId, slug, patch)

    @PostMapping("/sync")
    @Operation(summary = "Push offline changes and pull everything changed since your last sync")
    fun sync(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: SyncRequest): SyncResponse =
        progress.sync(jwt.userId, request)
}
