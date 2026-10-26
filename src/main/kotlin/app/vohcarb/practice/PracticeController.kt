package app.vohcarb.practice

import app.vohcarb.auth.userId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/me/practice")
@Tag(name = "Practice", description = "Spaced-repetition review of your saved words, with quiz questions")
@SecurityRequirement(name = "bearer")
class PracticeController(private val practice: PracticeService) {

    @GetMapping("/session")
    @Operation(summary = "Words to practise now (due reviews, then new saved words), each with a quiz question")
    fun session(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) size: Int,
    ): PracticeSession = practice.session(jwt.userId, size)

    @PostMapping("/reviews")
    @Operation(summary = "Record answers (AGAIN, HARD, GOOD or EASY); safe to re-send from offline")
    fun review(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: ReviewsRequest): ReviewsResponse =
        practice.review(jwt.userId, request)

    @GetMapping("/stats")
    @Operation(summary = "Due reviews, new words, reviews today and your streak")
    fun stats(@AuthenticationPrincipal jwt: Jwt): PracticeStats = practice.stats(jwt.userId)
}
