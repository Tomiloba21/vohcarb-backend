package app.vohcarb.practice

import app.vohcarb.catalog.Word
import app.vohcarb.catalog.WordRepository
import app.vohcarb.catalog.toDetail
import app.vohcarb.practice.srs.CardState
import app.vohcarb.practice.srs.Scheduler
import app.vohcarb.user.AppUserRepository
import app.vohcarb.user.now
import jakarta.persistence.EntityManager
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Practice = spaced repetition over the user's saved words (known words are left out).
 * A saved word becomes a card on its first review; [Scheduler] decides when it's due again.
 */
@Service
@Transactional
class PracticeService(
    private val em: EntityManager,
    private val users: AppUserRepository,
    private val words: WordRepository,
    private val cards: ReviewCardRepository,
    private val logs: ReviewLogRepository,
    private val streaks: StreakService,
    private val quiz: QuizService,
) {

    @Transactional(readOnly = true)
    fun session(userId: Long, size: Int): PracticeSession {
        val (due, new) = counts(userId, now())
        val rows = em.createQuery(
            """
            SELECT uw.word, c FROM UserWord uw
            LEFT JOIN ReviewCard c ON c.userId = uw.userId AND c.word = uw.word
            WHERE uw.userId = :userId AND uw.saved = TRUE AND uw.known = FALSE AND (c IS NULL OR c.dueAt <= :now)
            ORDER BY CASE WHEN c IS NULL THEN 1 ELSE 0 END, c.dueAt, uw.changedAt DESC
            """.trimIndent(),
            Array<Any?>::class.java,
        )
            .setParameter("userId", userId)
            .setParameter("now", now())
            .setMaxResults(size)
            .resultList

        val sessionWords = rows.map { it[0] as Word }
        val questions = quiz.questions(sessionWords)
        val items = rows.mapIndexed { i, (word, card) ->
            PracticeItem((word as Word).toDetail(), (card as ReviewCard?)?.toDto(), questions[i])
        }
        return PracticeSession(due, new, items)
    }

    fun review(userId: Long, request: ReviewsRequest): ReviewsResponse {
        // Serialises one user's concurrent review uploads.
        users.lockById(userId) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists")
        val serverNow = now()

        val bySlug = words.findAllBySlugIn(request.reviews.map { it.word }.toSet()).associateBy { it.slug }
        val cardsByWord = cards.findByUserIdAndWordIdIn(userId, bySlug.values.map { it.id })
            .associateBy { it.word.id }.toMutableMap()

        var applied = 0
        var ignored = 0
        for (input in request.reviews.filter { it.word in bySlug }.sortedBy { it.reviewedAt }) {
            val word = bySlug.getValue(input.word)
            // Device clocks ahead of ours are capped; precision matches the database so re-sends are detected.
            val at = minOf(input.reviewedAt, serverNow).truncatedTo(ChronoUnit.MICROS)
            if (logs.existsByUserIdAndWordIdAndReviewedAt(userId, word.id, at)) {
                ignored++
                continue
            }
            val card = cardsByWord[word.id]
            if (card != null && !at.isAfter(card.lastReviewedAt)) {
                // Older than what the card already knows (e.g. a second device syncing late): keep it for stats only.
                logs.save(ReviewLog(userId, word, input.grade, at, card.intervalDays))
                ignored++
                continue
            }

            val schedule = Scheduler.review(card?.state ?: CardState.NEW, input.grade)
            val dueAt = at.plus(Duration.ofMinutes(schedule.dueInMinutes))
            val updated = card ?: ReviewCard(userId, word, 0, 0, 0, 0, dueAt, at)
            updated.state = schedule.state
            updated.dueAt = dueAt
            updated.lastReviewedAt = at
            cardsByWord[word.id] = cards.save(updated)
            logs.save(ReviewLog(userId, word, input.grade, at, schedule.state.intervalDays))
            applied++
        }

        val touched = request.reviews.mapNotNull { bySlug[it.word]?.id }.toSet()
        return ReviewsResponse(
            applied = applied,
            ignored = ignored,
            unknownWords = request.reviews.map { it.word }.filter { it !in bySlug }.distinct(),
            cards = touched.mapNotNull { cardsByWord[it]?.toDto() },
        )
    }

    @Transactional(readOnly = true)
    fun stats(userId: Long): PracticeStats {
        val now = now()
        val streak = streaks.summary(userId, now)
        val (due, new) = counts(userId, now)
        return PracticeStats(due, new, cards.countByUserId(userId), streak.reviewedToday, streak.streakDays)
    }

    /** (reviews due now, saved words never reviewed) */
    private fun counts(userId: Long, now: Instant): Pair<Long, Long> {
        val row = em.createQuery(
            """
            SELECT
              COALESCE(SUM(CASE WHEN c IS NOT NULL AND c.dueAt <= :now THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN c IS NULL THEN 1 ELSE 0 END), 0)
            FROM UserWord uw
            LEFT JOIN ReviewCard c ON c.userId = uw.userId AND c.word = uw.word
            WHERE uw.userId = :userId AND uw.saved = TRUE AND uw.known = FALSE
            """.trimIndent(),
            Array<Any?>::class.java,
        )
            .setParameter("userId", userId)
            .setParameter("now", now)
            .singleResult
        return (row[0] as Number).toLong() to (row[1] as Number).toLong()
    }
}
