package app.vohcarb.progress

import app.vohcarb.catalog.Difficulty
import app.vohcarb.catalog.Word
import app.vohcarb.catalog.WordDetailDto
import app.vohcarb.catalog.toDetail
import app.vohcarb.user.UserPreferences
import app.vohcarb.user.UserPreferencesRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

@Service
@Transactional(readOnly = true)
class FeedService(
    private val em: EntityManager,
    private val preferences: UserPreferencesRepository,
) {

    /**
     * Random words for the user's feed: within their level range and categories, skipping sensitive words and
     * words they have already seen or marked as known. Words stay in the feed until the app reports them as seen,
     * so fetching again before that returns new random picks from the same pool.
     */
    fun feed(userId: Long, size: Int?, random: Random = Random.Default): List<WordDetailDto> {
        val prefs = preferences.findById(userId).orElseGet { UserPreferences(userId = userId) }
        val categoryIds = prefs.categories.map { it.id }

        // Shuffled here rather than with ORDER BY RANDOM(): that function is named differently in every database
        // (H2 has random(), MariaDB only rand()). Only ids travel, so even ~60k candidates are cheap.
        val candidates = em.createQuery(
            """
            SELECT w.id FROM Word w
            WHERE w.sensitive = FALSE
              AND COALESCE(w.difficulty, :rare) BETWEEN :min AND :max
              AND (:allCategories = TRUE OR EXISTS (
                    SELECT 1 FROM Word w2 JOIN w2.categories c WHERE w2 = w AND c.id IN :categoryIds))
              AND NOT EXISTS (
                    SELECT 1 FROM UserWord uw
                    WHERE uw.userId = :userId AND uw.word = w AND (uw.seen = TRUE OR uw.known = TRUE))
            """.trimIndent(),
            Long::class.javaObjectType,
        )
            .setParameter("rare", Difficulty.RARE.level.toShort())
            .setParameter("min", prefs.minDifficulty)
            .setParameter("max", prefs.maxDifficulty)
            .setParameter("allCategories", categoryIds.isEmpty())
            .setParameter("categoryIds", categoryIds.ifEmpty { listOf(-1L) }) // IN () is invalid SQL
            .setParameter("userId", userId)
            .resultList

        val picked = candidates.shuffled(random).take(size ?: prefs.wordsPerDay.toInt())
        if (picked.isEmpty()) return emptyList()
        val byId = em.createQuery("SELECT w FROM Word w WHERE w.id IN :ids", Word::class.java)
            .setParameter("ids", picked)
            .resultList
            .associateBy { it.id }
        return picked.mapNotNull { byId[it]?.toDetail() }
    }
}
