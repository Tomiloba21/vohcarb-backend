package app.vohcarb.practice

import app.vohcarb.catalog.WordDetailDto
import app.vohcarb.practice.srs.Grade
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CardDto(
    val word: String,
    val repetitions: Int,
    val easeFactor: Double,
    val intervalDays: Int,
    val lapses: Int,
    val dueAt: Instant,
    val lastReviewedAt: Instant,
)

data class PracticeItem(
    val word: WordDetailDto,
    /** Null for a saved word that has never been reviewed. */
    val card: CardDto?,
    /** Null only if the catalog has no other words to use as wrong answers. */
    val question: QuizQuestion?,
)

data class PracticeSession(
    /** Reviews due now, in total (the session holds at most `size` items). */
    val reviewsDue: Long,
    /** Saved words that haven't been reviewed yet, in total. */
    val newWords: Long,
    /** Due reviews first (most overdue first), then new words (most recently saved first). */
    val items: List<PracticeItem>,
)

data class ReviewInput(
    @field:NotBlank val word: String,
    val grade: Grade,
    /** When the user answered, on the device. */
    val reviewedAt: Instant,
)

data class ReviewsRequest(@field:Size(max = 200) @field:Valid val reviews: List<ReviewInput>)

data class ReviewsResponse(
    /** Reviews that moved a card forward. */
    val applied: Int,
    /** Reviews already received before (re-sent from offline) or older than the card's last review. */
    val ignored: Int,
    val unknownWords: List<String>,
    /** The current state of every card the request touched. */
    val cards: List<CardDto>,
)

data class PracticeStats(
    val reviewsDue: Long,
    val newWords: Long,
    val totalCards: Long,
    val reviewedToday: Int,
    /** Days in a row with at least one review, counting today if done, otherwise up to yesterday. */
    val streakDays: Int,
)

fun ReviewCard.toDto() =
    CardDto(word.slug, repetitions, easePermille / 1000.0, intervalDays, lapses, dueAt, lastReviewedAt)
