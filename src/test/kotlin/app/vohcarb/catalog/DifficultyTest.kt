package app.vohcarb.catalog

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DifficultyTest {

    @Test
    fun `ranks map to levels at the cut-offs`() {
        assertEquals(Difficulty.EVERYDAY, Difficulty.forRank(1))
        assertEquals(Difficulty.EVERYDAY, Difficulty.forRank(5_000))
        assertEquals(Difficulty.COMMON, Difficulty.forRank(5_001))
        assertEquals(Difficulty.INTERMEDIATE, Difficulty.forRank(50_000))
        assertEquals(Difficulty.ADVANCED, Difficulty.forRank(150_000))
        assertEquals(Difficulty.RARE, Difficulty.forRank(150_001))
        assertEquals(Difficulty.RARE, Difficulty.forRank(null))
    }
}
