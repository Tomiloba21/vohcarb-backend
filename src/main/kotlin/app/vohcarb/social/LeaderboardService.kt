package app.vohcarb.social

import app.vohcarb.practice.ReviewCardRepository
import app.vohcarb.practice.ReviewLogRepository
import app.vohcarb.practice.StreakService
import app.vohcarb.user.now
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Service
@Transactional(readOnly = true)
class LeaderboardService(
    private val groups: GroupService,
    private val members: GroupMemberRepository,
    private val logs: ReviewLogRepository,
    private val cards: ReviewCardRepository,
    private val streaks: StreakService,
) {

    /** Members ranked by answers in the last [days] days, then by streak. Equal scores share a rank. */
    fun leaderboard(userId: Long, groupId: Long, days: Int): LeaderboardDto {
        groups.requireMember(userId, groupId)
        val now = now()
        val since = now.minus(Duration.ofDays(days.toLong()))
        val group = members.findMembers(groupId)
        val ids = group.map { it.user.id!! }

        val reviews = logs.countByUserSince(ids, since).toCounts()
        val mastered = cards.countMatureByUser(ids, MASTERED_INTERVAL_DAYS).toCounts()

        val scored = group.map { m ->
            val id = m.user.id!!
            Scored(id, m.user.displayName, reviews[id] ?: 0, streaks.summary(id, now).streakDays, mastered[id] ?: 0)
        }.sortedWith(compareByDescending<Scored> { it.reviews }.thenByDescending { it.streakDays }.thenBy { it.name.lowercase() })

        var rank = 0
        var previous: Pair<Long, Int>? = null
        val rows = scored.mapIndexed { index, s ->
            val score = s.reviews to s.streakDays
            if (score != previous) rank = index + 1
            previous = score
            LeaderboardRow(rank, s.userId, s.name, s.reviews, s.streakDays, s.mastered, s.userId == userId)
        }
        return LeaderboardDto(days, since, rows)
    }

    private data class Scored(val userId: Long, val name: String, val reviews: Long, val streakDays: Int, val mastered: Long)

    private fun List<Array<Any>>.toCounts() = associate { (id, count) -> (id as Number).toLong() to (count as Number).toLong() }

    private companion object {
        /** A word counts as mastered once it's scheduled three weeks or more ahead. */
        const val MASTERED_INTERVAL_DAYS = 21
    }
}
