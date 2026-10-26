package app.vohcarb.practice.srs

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Spaced-repetition scheduling (SM-2, as used by SuperMemo 2 and, with tweaks, Anki).
// Plain Kotlin with no dependencies on purpose: the mobile app can copy this file into its KMP code
// and schedule reviews offline exactly like the server does.

enum class Grade { AGAIN, HARD, GOOD, EASY }

data class CardState(
    /** Successful reviews in a row; back to 0 after AGAIN. */
    val repetitions: Int = 0,
    /** How fast intervals grow. Starts at 2.5, drops when the word is hard, rises when easy. */
    val easeFactor: Double = INITIAL_EASE,
    /** Days until the next review (0 while relearning). */
    val intervalDays: Int = 0,
    /** Times the word was forgotten after having been learned. */
    val lapses: Int = 0,
) {
    companion object {
        const val INITIAL_EASE = 2.5
        val NEW = CardState()
    }
}

data class Schedule(val state: CardState, val dueInMinutes: Long)

object Scheduler {
    const val MIN_EASE = 1.3
    const val MAX_EASE = 3.0
    const val MAX_INTERVAL_DAYS = 365
    const val RELEARN_MINUTES = 10L
    private const val MINUTES_PER_DAY = 24L * 60

    fun review(card: CardState, grade: Grade): Schedule {
        if (grade == Grade.AGAIN) {
            val state = card.copy(
                repetitions = 0,
                easeFactor = ease(card.easeFactor - 0.2),
                intervalDays = 0,
                lapses = card.lapses + if (card.repetitions > 0) 1 else 0,
            )
            return Schedule(state, RELEARN_MINUTES)
        }

        val previous = card.intervalDays
        val interval = when (grade) {
            Grade.HARD -> if (card.repetitions == 0) 1 else grow(previous, 1.2)
            Grade.GOOD -> when (card.repetitions) {
                0 -> 1
                1 -> max(3, previous + 1)
                else -> grow(previous, card.easeFactor)
            }
            Grade.EASY -> if (card.repetitions == 0) 4 else grow(previous, card.easeFactor * 1.3)
            Grade.AGAIN -> error("handled above")
        }.coerceAtMost(MAX_INTERVAL_DAYS)

        val easeChange = when (grade) {
            Grade.HARD -> -0.15
            Grade.EASY -> 0.15
            else -> 0.0
        }
        val state = card.copy(
            repetitions = card.repetitions + 1,
            easeFactor = ease(card.easeFactor + easeChange),
            intervalDays = interval,
        )
        return Schedule(state, interval * MINUTES_PER_DAY)
    }

    /** Next interval: previous × factor, but always at least one day longer than before. */
    private fun grow(previous: Int, factor: Double) = max(previous + 1, (previous * factor).roundToInt())

    private fun ease(value: Double) = min(MAX_EASE, max(MIN_EASE, value))
}
