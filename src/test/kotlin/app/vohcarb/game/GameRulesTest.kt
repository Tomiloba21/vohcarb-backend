package app.vohcarb.game

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameRulesTest {

    @Test
    fun `answers are compared without case, accents, spaces or punctuation`() {
        assertEquals("saopaulo", AnswerText.normalize(" São Paulo "))
        assertEquals("bear", AnswerText.normalize("BEAR!"))
        assertEquals("", AnswerText.normalize("  "))
    }

    @Test
    fun `name-place-animal-thing never uses q x or z, nor the same letter twice`() {
        repeat(50) { seed ->
            val letters = NpatRules.letters(Random(seed))
            assertEquals(NpatRules.ROUNDS, letters.distinct().size)
            assertTrue(letters.none { it in "qxz" })
        }
    }

    @Test
    fun `unique answers score 10, shared ones 5, invalid ones nothing`() {
        val points = NpatRules.score(mapOf("ada" to "bear", "bo" to "bear", "cy" to "bison", "di" to null))
        assertEquals(mapOf("ada" to 5, "bo" to 5, "cy" to 10, "di" to 0), points)
    }

    @Test
    fun `an unlisted answer stands unless most voters reject it`() {
        assertTrue(NpatRules.unlistedStands(accepts = 0, rejects = 0, votingPossible = true))
        assertFalse(NpatRules.unlistedStands(accepts = 0, rejects = 0, votingPossible = false))
        assertTrue(NpatRules.unlistedStands(accepts = 1, rejects = 1, votingPossible = true))
        assertFalse(NpatRules.unlistedStands(accepts = 1, rejects = 2, votingPossible = true))
    }

    @Test
    fun `masks show the first letter and the right share of the rest`() {
        val word = "serendipity"
        val easy = CompleteRules.mask(word, HintLevel.EASY, Random(1))
        val hard = CompleteRules.mask(word, HintLevel.HARD, Random(1))
        assertEquals('s', easy.first())
        assertEquals(6, easy.count { it != CompleteRules.HIDDEN }) // about half of 11
        assertEquals("s__________", hard)
        assertEquals(CompleteRules.HIDDEN, easy.last())
        // Matches the word wherever a letter shows
        assertTrue(easy.zip(word).all { (m, w) -> m == CompleteRules.HIDDEN || m == w })
    }

    @Test
    fun `revealing shows the leftmost hidden letter`() {
        assertEquals("se_________", CompleteRules.reveal("s__________", "serendipity"))
    }

    @Test
    fun `complete-the-word points reward the hint level and speed, and charge for reveals`() {
        assertEquals(15, CompleteRules.points(true, HintLevel.EASY, elapsedMs = 0, reveals = 0))
        assertEquals(30, CompleteRules.points(true, HintLevel.HARD, elapsedMs = 20_000, reveals = 0))
        // 20 for medium, 3 for answering at half time (2.5 rounds up), minus 3 for one revealed letter
        assertEquals(20, CompleteRules.points(true, HintLevel.MEDIUM, elapsedMs = 10_000, reveals = 1))
        assertEquals(0, CompleteRules.points(false, HintLevel.HARD, elapsedMs = 0, reveals = 0))
        assertEquals(0, CompleteRules.points(true, HintLevel.EASY, elapsedMs = 20_000, reveals = 5))
    }
}
