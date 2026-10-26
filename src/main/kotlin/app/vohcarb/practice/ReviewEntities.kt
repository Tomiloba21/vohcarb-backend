package app.vohcarb.practice

import app.vohcarb.catalog.Word
import app.vohcarb.practice.srs.CardState
import app.vohcarb.practice.srs.Grade
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import kotlin.math.roundToInt

@Entity
@Table(name = "review_card")
class ReviewCard(
    val userId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "word_id")
    val word: Word,

    var repetitions: Int,
    var easePermille: Int,
    var intervalDays: Int,
    var lapses: Int,
    var dueAt: Instant,
    var lastReviewedAt: Instant,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
) {
    var state: CardState
        get() = CardState(repetitions, easePermille / 1000.0, intervalDays, lapses)
        set(value) {
            repetitions = value.repetitions
            easePermille = (value.easeFactor * 1000).roundToInt()
            intervalDays = value.intervalDays
            lapses = value.lapses
        }
}

@Entity
@Table(name = "review_log")
class ReviewLog(
    val userId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "word_id")
    val word: Word,

    @Enumerated(EnumType.STRING) val grade: Grade,
    val reviewedAt: Instant,
    val intervalDays: Int,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
)

interface ReviewCardRepository : JpaRepository<ReviewCard, Long> {

    fun findByUserIdAndWordIdIn(userId: Long, wordIds: Collection<Long>): List<ReviewCard>

    fun countByUserId(userId: Long): Long

    /** (userId, cards with an interval of at least [minInterval] days) for each of [userIds] that has any. */
    @Query("SELECT c.userId, COUNT(c) FROM ReviewCard c WHERE c.userId IN :userIds AND c.intervalDays >= :minInterval GROUP BY c.userId")
    fun countMatureByUser(userIds: Collection<Long>, minInterval: Int): List<Array<Any>>
}

interface ReviewLogRepository : JpaRepository<ReviewLog, Long> {

    fun existsByUserIdAndWordIdAndReviewedAt(userId: Long, wordId: Long, reviewedAt: Instant): Boolean

    @Query("SELECT l.reviewedAt FROM ReviewLog l WHERE l.userId = :userId AND l.reviewedAt > :after")
    fun reviewTimes(userId: Long, after: Instant): List<Instant>

    /** (userId, reviews since [since]) for each of [userIds] that has any. */
    @Query("SELECT l.userId, COUNT(l) FROM ReviewLog l WHERE l.userId IN :userIds AND l.reviewedAt >= :since GROUP BY l.userId")
    fun countByUserSince(userIds: Collection<Long>, since: Instant): List<Array<Any>>
}
