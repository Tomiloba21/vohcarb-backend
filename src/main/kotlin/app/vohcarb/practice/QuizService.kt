package app.vohcarb.practice

import app.vohcarb.catalog.Difficulty
import app.vohcarb.catalog.Sense
import app.vohcarb.catalog.Word
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

enum class QuestionType {
    /** prompt is the word; options are definitions. */
    MEANING_OF_WORD,
    /** prompt is a definition (with the word itself blanked out); options are words. */
    WORD_FOR_MEANING,
}

/** A multiple-choice question. The answer is included so the app can grade offline. */
data class QuizQuestion(val type: QuestionType, val prompt: String, val options: List<String>, val correctIndex: Int)

@Service
@Transactional(readOnly = true)
class QuizService(private val em: EntityManager) {

    /**
     * One question per word (null where no wrong answers could be found). Wrong answers for the whole batch come
     * from a single random pool of words, fetched by id, instead of shuffling the full catalog per question.
     */
    fun questions(words: List<Word>, random: Random = Random.Default): List<QuizQuestion?> {
        if (words.isEmpty()) return emptyList()
        val pool = randomMainSenses(random)
        return words.map { question(it, pool, random) }
    }

    private fun randomMainSenses(random: Random): List<Sense> {
        val maxId = em.createQuery("SELECT MAX(w.id) FROM Word w", Long::class.javaObjectType).singleResult ?: return emptyList()
        // Word ids are dense (the importer numbers them 1..n), so random ids are random words.
        val ids = if (maxId <= POOL_SIZE.toLong()) (1..maxId).toList() else generateSequence { random.nextLong(1, maxId + 1) }.distinct().take(POOL_SIZE).toList()
        return em.createQuery("SELECT s FROM Sense s JOIN FETCH s.word w WHERE s.sortOrder = 0 AND w.sensitive = FALSE AND w.id IN :ids", Sense::class.java)
            .setParameter("ids", ids)
            .resultList
    }

    private fun question(word: Word, pool: List<Sense>, random: Random): QuizQuestion? {
        val sense = word.senses.firstOrNull() ?: return null
        // A definition that contains the word gives the answer away, so ask for the word with it blanked out.
        val type = if (mentionsWord(sense.definition, word.lemma)) QuestionType.WORD_FOR_MEANING else QuestionType.entries.random(random)
        val pick: (Sense) -> String = if (type == QuestionType.MEANING_OF_WORD) Sense::definition else { s -> s.word.lemma }
        val correct = pick(sense)

        val wrong = pickDistractors(word, sense, pool, random).map(pick).filter { it != correct }.distinct().take(OPTIONS - 1)
        if (wrong.isEmpty()) return null

        val options = (wrong + correct).shuffled(random)
        val prompt = if (type == QuestionType.MEANING_OF_WORD) word.lemma else blankOut(sense.definition, word.lemma)
        return QuizQuestion(type, prompt, options, options.indexOf(correct))
    }

    companion object {
        const val OPTIONS = 4
        const val POOL_SIZE = 400
        const val BLANK = "____"

        /**
         * Candidate wrong answers, best first: same part of speech and a level within one of the word's, then same
         * part of speech, then anything. Words sharing the word's definition are synonyms and never used.
         */
        fun pickDistractors(word: Word, sense: Sense, pool: List<Sense>, random: Random): List<Sense> {
            val level = word.level()
            val usable = pool.filter { it.word.id != word.id && it.definition != sense.definition }.shuffled(random)
            val tiers = listOf<(Sense) -> Boolean>(
                { it.partOfSpeech == sense.partOfSpeech && abs(it.word.level() - level) <= 1 },
                { it.partOfSpeech == sense.partOfSpeech },
                { true },
            )
            return tiers.flatMap { tier -> usable.filter(tier) }.distinctBy { it.id }
        }

        private fun Word.level() = (difficulty ?: Difficulty.RARE.level.toShort()).toInt()

        private val TOKEN = Regex("[A-Za-z'-]+")

        fun mentionsWord(definition: String, lemma: String) = TOKEN.findAll(definition).any { isFormOf(it.value, lemma) }

        /** Hides the word and forms of it (cancel → cancelling) when its own definition mentions it. */
        fun blankOut(definition: String, lemma: String): String =
            TOKEN.replace(definition) { if (isFormOf(it.value, lemma)) BLANK else it.value }

        /**
         * Rough "same word" test with no stemmer: short words must appear whole at the start of the token (dog → dogs),
         * longer ones need a shared start of over half their length (cancellation ~ cancelling, but not constant).
         */
        private fun isFormOf(token: String, lemma: String): Boolean {
            val needed = if (lemma.length <= 5) lemma.length else maxOf(5, (lemma.length * 0.55).roundToInt())
            return token.lowercase().commonPrefixWith(lemma.lowercase()).length >= needed
        }
    }
}
