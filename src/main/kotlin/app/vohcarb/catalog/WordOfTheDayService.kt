package app.vohcarb.catalog

import jakarta.persistence.EntityManager
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import kotlin.random.Random

data class WordOfTheDayDto(val date: LocalDate, val word: WordDetailDto)

/**
 * A word picked from the date alone, so everyone asking on the same day gets the same one without storing anything.
 * Different seeds (a group's id) give different words; seed 0 is the public word of the day.
 */
@Service
@Transactional(readOnly = true)
class WordOfTheDayService(private val em: EntityManager) {

    fun forDate(date: LocalDate, seed: Long = 0): WordOfTheDayDto {
        // Intermediate and advanced words make the best daily words; any word will do on a tiny catalog.
        val word = pick(date, seed, "AND w.difficulty BETWEEN 3 AND 4") ?: pick(date, seed, "")
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "The catalog is empty")
        return WordOfTheDayDto(date, word.toDetail())
    }

    private fun pick(date: LocalDate, seed: Long, filter: String): Word? {
        val where = "WHERE w.sensitive = FALSE $filter"
        val count = em.createQuery("SELECT COUNT(w) FROM Word w $where", Long::class.javaObjectType).singleResult
        if (count == 0L) return null
        // kotlin.random.Random with a fixed seed gives the same sequence on every JVM.
        val offset = Random(date.toEpochDay() * 1_000_003 + seed).nextLong(count)
        return em.createQuery("SELECT w FROM Word w $where ORDER BY w.id", Word::class.java)
            .setFirstResult(offset.toInt())
            .setMaxResults(1)
            .resultList
            .firstOrNull()
    }
}
