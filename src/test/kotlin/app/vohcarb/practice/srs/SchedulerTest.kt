package app.vohcarb.practice.srs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerTest {

    private fun reviews(vararg grades: Grade): List<Schedule> {
        var state = CardState.NEW
        return grades.map { grade -> Scheduler.review(state, grade).also { state = it.state } }
    }

    @Test
    fun `good answers grow the interval 1, 3, then by the ease factor`() {
        val days = reviews(Grade.GOOD, Grade.GOOD, Grade.GOOD, Grade.GOOD).map { it.state.intervalDays }
        assertEquals(listOf(1, 3, 8, 20), days) // 3 × 2.5 = 7.5 → 8, 8 × 2.5 = 20
    }

    @Test
    fun `due time is the interval in minutes`() {
        assertEquals(24L * 60, Scheduler.review(CardState.NEW, Grade.GOOD).dueInMinutes)
        assertEquals(4L * 24 * 60, Scheduler.review(CardState.NEW, Grade.EASY).dueInMinutes)
    }

    @Test
    fun `again sends the word back in ten minutes and counts a lapse once learned`() {
        val (_, _, again) = reviews(Grade.GOOD, Grade.GOOD, Grade.AGAIN)
        assertEquals(Scheduler.RELEARN_MINUTES, again.dueInMinutes)
        assertEquals(0, again.state.repetitions)
        assertEquals(0, again.state.intervalDays)
        assertEquals(1, again.state.lapses)
        assertEquals(2.3, again.state.easeFactor, 1e-9)

        // Failing a brand-new word isn't a lapse: it was never learned.
        assertEquals(0, Scheduler.review(CardState.NEW, Grade.AGAIN).state.lapses)
    }

    @Test
    fun `relearning after a lapse starts again at one day`() {
        val schedules = reviews(Grade.GOOD, Grade.GOOD, Grade.GOOD, Grade.AGAIN, Grade.GOOD)
        assertEquals(1, schedules.last().state.intervalDays)
    }

    @Test
    fun `hard and easy move the ease factor within its bounds`() {
        val hard = reviews(*Array(20) { Grade.HARD }).last().state
        assertEquals(Scheduler.MIN_EASE, hard.easeFactor, 1e-9)
        val easy = reviews(*Array(20) { Grade.EASY }).last().state
        assertEquals(Scheduler.MAX_EASE, easy.easeFactor, 1e-9)
        assertEquals(Scheduler.MAX_INTERVAL_DAYS, easy.intervalDays)
    }

    @Test
    fun `intervals always grow on success, even with low ease`() {
        val intervals = reviews(*Array(10) { Grade.HARD }).map { it.state.intervalDays }
        assertTrue(intervals.zipWithNext().all { (a, b) -> b > a }, "$intervals")
    }
}
