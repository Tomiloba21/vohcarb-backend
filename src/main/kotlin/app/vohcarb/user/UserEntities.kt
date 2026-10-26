package app.vohcarb.user

import app.vohcarb.catalog.Category
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Current time at the precision the DATETIME(6) columns store, so saved and returned values match. */
fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

@Entity
@Table(name = "app_user")
class AppUser(
    var email: String?,
    var displayName: String,
    val createdAt: Instant = now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
)

@Entity
@Table(name = "user_identity")
class UserIdentity(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    val user: AppUser,

    val provider: String,
    val subject: String,
    val createdAt: Instant = now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
)

@Entity
@Table(name = "user_preferences")
class UserPreferences(
    @Id val userId: Long,
    var minDifficulty: Short = DEFAULT_MIN_DIFFICULTY,
    var maxDifficulty: Short = DEFAULT_MAX_DIFFICULTY,
    var wordsPerDay: Short = DEFAULT_WORDS_PER_DAY,
    var timezone: String = "UTC",
    var updatedAt: Instant = now(),

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_preferred_category",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "category_id")],
    )
    var categories: MutableSet<Category> = mutableSetOf(),

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_reminder_time", joinColumns = [JoinColumn(name = "user_id")])
    @Column(name = "remind_at")
    var reminderTimes: MutableSet<LocalTime> = mutableSetOf(LocalTime.of(9, 0)),
) {
    companion object {
        // Level 5 is mostly specialist jargon, so new users start on 2–4.
        const val DEFAULT_MIN_DIFFICULTY: Short = 2
        const val DEFAULT_MAX_DIFFICULTY: Short = 4
        const val DEFAULT_WORDS_PER_DAY: Short = 10
    }
}
