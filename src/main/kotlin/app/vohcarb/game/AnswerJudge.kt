package app.vohcarb.game

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** One checked answer. */
data class Judged(val text: String, val normalized: String, val verdict: Verdict, val points: Int = 0)

/** A Name-Place-Animal-Thing answer to check: [key] says whose and where, for the caller. */
data class NpatEntry<K>(val key: K, val letter: Char, val category: NpatRules.Category, val text: String)

/** Checks answers for daily challenges and live matches alike. Looks lists up in batches. */
@Service
@Transactional(readOnly = true)
class AnswerJudge(private val lists: AnswerLists) {

    fun <K> npat(entries: List<NpatEntry<K>>): Map<K, Judged> {
        val prepared = entries.mapNotNull { e ->
            e.text.trim().take(MAX_ANSWER).takeIf { it.isNotEmpty() }?.let { e to Pair(it, AnswerText.normalize(it)) }
        }
        fun keys(category: NpatRules.Category) = prepared.filter { it.first.category == category }.map { it.second.second }
        val names = lists.knownNames(keys(NpatRules.Category.NAME))
        val places = lists.knownPlaces(keys(NpatRules.Category.PLACE))
        val animals = lists.wordsInCategory(AnswerLists.ANIMALS, keys(NpatRules.Category.ANIMAL)).keys
        val things = lists.wordsInCategories(AnswerLists.THINGS, keys(NpatRules.Category.THING)).keys
        return prepared.associate { (e, t) ->
            val (text, normalized) = t
            val verdict = when {
                normalized.isEmpty() || normalized.first() != e.letter -> Verdict.INVALID
                e.category == NpatRules.Category.NAME -> listedOr(normalized in names, normalized)
                e.category == NpatRules.Category.PLACE -> listedOr(normalized in places, normalized)
                e.category == NpatRules.Category.ANIMAL -> if (normalized in animals) Verdict.VALID else Verdict.INVALID
                else -> if (normalized in things) Verdict.VALID else Verdict.INVALID
            }
            e.key to Judged(text, normalized, verdict)
        }
    }

    /** Distinct words in order typed; each in the category scores its level. */
    fun sprint(category: String, words: List<String>): List<Judged> {
        val typed = words.map { it.trim().take(MAX_ANSWER) }.filter { it.isNotEmpty() }
            .map { it to AnswerText.normalize(it) }.filter { it.second.isNotEmpty() }.distinctBy { it.second }
        val known = lists.wordsInCategory(category, typed.map { it.second })
        return typed.map { (text, normalized) ->
            if (normalized in known) {
                Judged(text, normalized, Verdict.VALID, SprintRules.points(known[normalized]))
            } else {
                Judged(text, normalized, Verdict.INVALID)
            }
        }
    }

    fun complete(word: CompleteWord, hint: HintLevel, answer: String, elapsedMs: Int?, reveals: Int): Judged {
        val text = answer.trim().take(MAX_ANSWER)
        val normalized = AnswerText.normalize(text)
        val correct = normalized == word.word
        return Judged(text, normalized, if (correct) Verdict.VALID else Verdict.INVALID, CompleteRules.points(correct, hint, elapsedMs, reveals))
    }

    /** Names and places missing from the lists go to a vote, unless they're too short to be real. */
    private fun listedOr(listed: Boolean, normalized: String) = when {
        listed -> Verdict.VALID
        normalized.length >= 2 -> Verdict.UNLISTED
        else -> Verdict.INVALID
    }

    companion object {
        const val MAX_ANSWER = 64
    }
}
