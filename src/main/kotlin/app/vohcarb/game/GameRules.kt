package app.vohcarb.game

import java.text.Normalizer
import kotlin.math.roundToInt
import kotlin.random.Random

// Rules and scoring for the word games (docs/games.md). Plain Kotlin with no Spring or database, so it's easy
// to test and the live-play rooms can reuse it.

enum class Game { NAME_PLACE_ANIMAL_THING, CATEGORY_SPRINT, COMPLETE_THE_WORD }

/** How much of the word complete-the-word shows. */
enum class HintLevel(val multiplier: Int, val minLevel: Int, val maxLevel: Int) {
    EASY(1, 1, 2),
    MEDIUM(2, 2, 3),
    HARD(3, 3, 4),
}

/** Each daily challenge: a game, and for complete-the-word a hint level. */
enum class Challenge(val game: Game, val hint: HintLevel?, val title: String, val description: String) {
    NAME_PLACE_ANIMAL_THING(
        Game.NAME_PLACE_ANIMAL_THING, null, "Name, Place, Animal, Thing",
        "5 letters, 60 seconds each: a name, a place, an animal and a thing starting with the letter.",
    ),
    CATEGORY_SPRINT(
        Game.CATEGORY_SPRINT, null, "Category sprint",
        "60 seconds to name as many words in the category as you can. Rarer words score more.",
    ),
    COMPLETE_THE_WORD_EASY(Game.COMPLETE_THE_WORD, HintLevel.EASY, "Complete the word: easy", "The meaning and half the letters."),
    COMPLETE_THE_WORD_MEDIUM(Game.COMPLETE_THE_WORD, HintLevel.MEDIUM, "Complete the word: medium", "The meaning and a third of the letters."),
    COMPLETE_THE_WORD_HARD(Game.COMPLETE_THE_WORD, HintLevel.HARD, "Complete the word: hard", "The meaning, the first letter and the length."),
    ;

    /** Time to play, for the whole challenge. */
    val timeLimitSeconds: Int
        get() = when (game) {
            Game.NAME_PLACE_ANIMAL_THING -> NpatRules.ROUNDS * NpatRules.ROUND_SECONDS
            Game.CATEGORY_SPRINT -> SprintRules.SECONDS
            Game.COMPLETE_THE_WORD -> CompleteRules.WORDS * CompleteRules.WORD_SECONDS
        }
}

enum class Verdict {
    VALID,
    INVALID,

    /** A Name or Place that isn't in the lists: valid unless other players reject it. */
    UNLISTED,
}

object AnswerText {
    private val MARKS = Regex("\\p{M}+")
    private val NOT_LETTER = Regex("[^a-z]")

    /** What answers are compared by: "São Paulo" → "saopaulo", "  Bear " → "bear". */
    fun normalize(text: String): String =
        Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NOT_LETTER, "")
}

object NpatRules {
    const val ROUNDS = 5
    const val ROUND_SECONDS = 60
    const val UNIQUE_POINTS = 10
    const val SHARED_POINTS = 5

    enum class Category { NAME, PLACE, ANIMAL, THING }

    /** Q, X and Z have too few answers in some categories. */
    val LETTERS = ('a'..'z').filter { it !in "qxz" }

    fun letters(random: Random): List<Char> = LETTERS.shuffled(random).take(ROUNDS)

    /**
     * Points for one category of one round across everyone who played it: [answers] maps each player to their
     * normalized answer, or null when it was invalid or empty. A valid answer nobody else gave scores 10; one that
     * others gave too scores 5.
     */
    fun <P> score(answers: Map<P, String?>): Map<P, Int> {
        val counts = answers.values.filterNotNull().groupingBy { it }.eachCount()
        return answers.mapValues { (_, answer) ->
            when {
                answer == null -> 0
                counts.getValue(answer) == 1 -> UNIQUE_POINTS
                else -> SHARED_POINTS
            }
        }
    }

    /**
     * An unlisted answer stands unless most of the people who voted on it rejected it. With no votes it stands
     * where voting is possible (groups) and fails where it isn't (the global challenge).
     */
    fun unlistedStands(accepts: Int, rejects: Int, votingPossible: Boolean): Boolean =
        if (accepts + rejects == 0) votingPossible else rejects <= accepts
}

object SprintRules {
    const val SECONDS = 60
    const val MAX_WORDS = 200

    /** A word scores its level: 1 (everyday) to 5 (rare). Unranked words count as rare. */
    fun points(level: Int?): Int = level ?: 5
}

object CompleteRules {
    const val WORDS = 10
    const val WORD_SECONDS = 20
    const val BASE_POINTS = 10
    const val MAX_SPEED_BONUS = 5
    const val REVEAL_COST = 3
    const val HIDDEN = '_'

    /**
     * The hint for a word: the first letter always shows; easy shows about half the letters, medium a third,
     * hard none of the rest. The last letter never shows on its own, so the word is never given away.
     */
    fun mask(word: String, hint: HintLevel, random: Random): String {
        val share = when (hint) {
            HintLevel.EASY -> 0.5
            HintLevel.MEDIUM -> 1.0 / 3
            HintLevel.HARD -> 0.0
        }
        val shown = ((word.length * share).roundToInt() - 1).coerceIn(0, word.length - 2)
        val extra = (1 until word.length - 1).shuffled(random).take(shown).toSet()
        return word.mapIndexed { i, c -> if (i == 0 || i in extra) c else HIDDEN }.joinToString("")
    }

    /** Shows the leftmost hidden letter ("Reveal a letter"). */
    fun reveal(mask: String, word: String): String {
        val i = mask.indexOf(HIDDEN)
        return if (i < 0) mask else mask.substring(0, i) + word[i] + mask.substring(i + 1)
    }

    /** A right answer: 10 × the hint level's multiplier, plus up to 5 for speed, minus 3 per revealed letter. */
    fun points(correct: Boolean, hint: HintLevel, elapsedMs: Int?, reveals: Int): Int {
        if (!correct) return 0
        val limit = WORD_SECONDS * 1000.0
        val elapsed = (elapsedMs ?: WORD_SECONDS * 1000).coerceIn(0, WORD_SECONDS * 1000)
        val speed = (MAX_SPEED_BONUS * (1 - elapsed / limit)).roundToInt()
        return (BASE_POINTS * hint.multiplier + speed - REVEAL_COST * reveals).coerceAtLeast(0)
    }
}
