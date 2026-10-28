package app.vohcarb.game

import app.vohcarb.catalog.CategoryRef
import app.vohcarb.catalog.Word
import app.vohcarb.practice.QuizService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.random.Random

/** What a daily challenge asks. Worked out from the date and scope alone, like the word of the day. */
sealed interface Content {
    data class Npat(val letters: List<Char>) : Content
    data class Sprint(val category: CategoryRef) : Content
    data class Complete(val hint: HintLevel, val words: List<CompleteWord>) : Content
}

/** [word] is the answer and never leaves the server; players get [definition], [partOfSpeech] and [mask]. */
data class CompleteWord(val word: String, val definition: String, val partOfSpeech: String, val mask: String)

@Service
@Transactional(readOnly = true)
class DailyContent(private val em: EntityManager) {

    /** [scopeKey]: 0 for the global challenge, else the group id (each group gets its own rounds). */
    fun forDay(challenge: Challenge, date: LocalDate, scopeKey: Long): Content =
        // kotlin.random.Random with a fixed seed gives the same sequence on every JVM.
        generate(challenge, Random(date.toEpochDay() * 1_000_003 + scopeKey * 7_919 + challenge.ordinal))

    /** Fresh rounds for a live match. */
    fun random(challenge: Challenge): Content = generate(challenge, Random.Default)

    private fun generate(challenge: Challenge, random: Random): Content {
        return when (challenge.game) {
            Game.NAME_PLACE_ANIMAL_THING -> Content.Npat(NpatRules.letters(random))
            Game.CATEGORY_SPRINT -> Content.Sprint(sprintCategory(random))
            Game.COMPLETE_THE_WORD -> challenge.hint!!.let { Content.Complete(it, completeWords(it, random)) }
        }
    }

    /** A category with plenty of everyday-to-intermediate words, so the sprint is fair; the biggest one on a tiny catalog. */
    private fun sprintCategory(random: Random): CategoryRef {
        val rows = em.createQuery(
            """
            SELECT c.slug, c.name, COUNT(w) FROM Word w JOIN w.categories c
            WHERE w.sensitive = FALSE AND w.difficulty <= 3
            GROUP BY c.slug, c.name ORDER BY c.slug
            """.trimIndent(),
            Array<Any>::class.java,
        ).resultList.map { Triple(it[0] as String, it[1] as String, (it[2] as Number).toLong()) }
        val eligible = rows.filter { it.third >= MIN_SPRINT_WORDS }
        val pick = if (eligible.isNotEmpty()) eligible.random(random) else rows.maxByOrNull { it.third }
        if (pick != null) return CategoryRef(pick.first, pick.second)
        // No ranked words at all: any category
        val any = em.createQuery("SELECT c.slug, c.name FROM Category c ORDER BY c.slug", Array<Any>::class.java).resultList
        check(any.isNotEmpty()) { "The catalog has no categories" }
        return any.random(random).let { CategoryRef(it[0] as String, it[1] as String) }
    }

    private fun completeWords(hint: HintLevel, random: Random): List<CompleteWord> {
        // Fall back to looser filters so a small catalog still gives a game.
        val filters = listOf(
            "AND w.difficulty BETWEEN ${hint.minLevel} AND ${hint.maxLevel} AND LENGTH(w.lemma) BETWEEN 4 AND 12",
            "AND LENGTH(w.lemma) BETWEEN 4 AND 12",
            "AND LENGTH(w.lemma) >= 3",
        )
        val picked = LinkedHashMap<String, CompleteWord>()
        for (filter in filters) {
            val where = "WHERE w.sensitive = FALSE $filter"
            val count = em.createQuery("SELECT COUNT(w) FROM Word w $where", Long::class.javaObjectType).singleResult
            if (count == 0L) continue
            val offsets = generateSequence { random.nextLong(count) }.take(MAX_ATTEMPTS).distinct()
            for (offset in offsets) {
                if (picked.size == CompleteRules.WORDS) break
                val word = em.createQuery("SELECT w FROM Word w $where ORDER BY w.id", Word::class.java)
                    .setFirstResult(offset.toInt()).setMaxResults(1).resultList.firstOrNull() ?: continue
                completeWord(word, hint, random)?.let { picked.putIfAbsent(it.word, it) }
            }
            if (picked.size == CompleteRules.WORDS) break
        }
        return picked.values.toList()
    }

    /** Only plain words with a meaning; the word itself is blanked out of its definition. */
    private fun completeWord(word: Word, hint: HintLevel, random: Random): CompleteWord? {
        if (!LETTERS_ONLY.matches(word.lemma)) return null
        val sense = word.senses.firstOrNull() ?: return null
        return CompleteWord(
            word = word.lemma,
            definition = QuizService.blankOut(sense.definition, word.lemma),
            partOfSpeech = sense.partOfSpeech,
            mask = CompleteRules.mask(word.lemma, hint, random),
        )
    }

    private companion object {
        const val MIN_SPRINT_WORDS = 150L
        const val MAX_ATTEMPTS = 60
        val LETTERS_ONLY = Regex("[a-z]+")
    }
}
