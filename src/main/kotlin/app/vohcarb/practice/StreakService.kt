package app.vohcarb.practice

import app.vohcarb.user.UserPreferencesRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StreakSummary(val reviewedToday: Int, val streakDays: Int)

/** Review streaks, counted in each user's own timezone. Shared by practice stats and group leaderboards. */
@Service
@Transactional(readOnly = true)
class StreakService(
    private val logs: ReviewLogRepository,
    private val preferences: UserPreferencesRepository,
) {

    fun summary(userId: Long, now: Instant): StreakSummary {
        val zone = preferences.findById(userId).map { ZoneId.of(it.timezone) }.orElse(UTC)
        val today = LocalDate.ofInstant(now, zone)
        val since = today.minusDays(WINDOW_DAYS).atStartOfDay(zone).toInstant()
        val days = logs.reviewTimes(userId, since).map { LocalDate.ofInstant(it, zone) }
        return StreakSummary(reviewedToday = days.count { it == today }, streakDays = currentStreak(days.toSet(), today))
    }

    companion object {
        /** How far back streaks are counted. */
        const val WINDOW_DAYS = 400L
        private val UTC: ZoneId = ZoneId.of("UTC")

        /** Days in a row with a review, ending today, or yesterday if today has none yet (the streak is still alive). */
        fun currentStreak(days: Set<LocalDate>, today: LocalDate): Int {
            var day = if (today in days) today else today.minusDays(1)
            var streak = 0
            while (day in days) {
                streak++
                day = day.minusDays(1)
            }
            return streak
        }
    }
}
