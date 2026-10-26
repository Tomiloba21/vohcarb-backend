package app.vohcarb.practice

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlankOutTest {

    @Test
    fun `hides the word and forms of it in its own definition`() {
        assertEquals("the act of ____", QuizService.blankOut("the act of jumping", "jump"))
        assertEquals("a ____ that ____ at night", QuizService.blankOut("a Dog that dogs at night", "dog"))
        assertEquals("the act of ____; calling off", QuizService.blankOut("the act of cancelling; calling off", "cancellation"))
    }

    @Test
    fun `leaves unrelated words alone`() {
        assertEquals("a constant ____", QuizService.blankOut("a constant construction", "construction"))
        assertEquals("a happening", QuizService.blankOut("a happening", "happy"))
    }

    @Test
    fun `detects definitions that give the word away`() {
        assertTrue(QuizService.mentionsWord("the act of cancelling", "cancellation"))
        assertFalse(QuizService.mentionsWord("lasting a very short time", "ephemeral"))
    }
}
