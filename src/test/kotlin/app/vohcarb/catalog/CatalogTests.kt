package app.vohcarb.catalog

import app.vohcarb.catalog.importer.FrequencyImporter
import app.vohcarb.catalog.importer.ImportStats
import app.vohcarb.catalog.importer.WordNetImporter
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val importer: WordNetImporter,
    @Autowired private val frequencyImporter: FrequencyImporter,
    @Autowired private val jdbc: JdbcTemplate,
) {

    private lateinit var stats: ImportStats
    private lateinit var levelCounts: Map<Difficulty, Int>

    @BeforeAll
    fun importFixture() {
        // Left by other test classes sharing this database
        for (table in listOf("review_log", "review_card", "user_word", "group_word")) jdbc.update("DELETE FROM $table")
        stats = fixture().use { importer.importStream(it, replace = true) }
        levelCounts = javaClass.getResourceAsStream("/frequency-fixture.txt")!!.use { frequencyImporter.importStream(it) }
    }

    private fun fixture() = javaClass.getResourceAsStream("/wordnet-fixture.xml")!!

    @Test
    fun `import keeps single lowercase words and joins senses from synsets`() {
        // dog, happy, joy, o'clock, zonk. Not: Canis (proper noun), domestic dog (phrase), ox (too short), zzz (no definition)
        assertEquals(ImportStats(words = 5, senses = 7, examples = 5, pronunciations = 3), stats)
    }

    @Test
    fun `import refuses to overwrite an existing catalog unless asked`() {
        assertFailsWith<IllegalStateException> { fixture().use { importer.importStream(it, replace = false) } }
    }

    @Test
    fun `word detail has pronunciations, ordered senses, examples and categories`() {
        mockMvc.get("/api/v1/words/dog").andExpect {
            status { isOk() }
            jsonPath("$.lemma") { value("dog") }
            jsonPath("$.pronunciations.length()") { value(2) }
            jsonPath("$.pronunciations[0].variety") { value("GB") }
            jsonPath("$.senses.length()") { value(3) }
            jsonPath("$.senses[0].partOfSpeech") { value("noun") }
            jsonPath("$.senses[0].category") { value("animals") }
            jsonPath("$.senses[2].partOfSpeech") { value("verb") }
            jsonPath("$.senses[2].examples[1].sentence") { value("the dog chased the rabbit") }
            jsonPath("$.categories[*].slug") { value(contains("animals", "movement", "people")) }
        }
    }

    @Test
    fun `example attribution and apostrophe slugs come through`() {
        mockMvc.get("/api/v1/words/happy").andExpect {
            jsonPath("$.senses[0].partOfSpeech") { value("adjective") }
            jsonPath("$.senses[0].examples[0].source") { value("Robert Louis Stevenson") }
        }
        mockMvc.get("/api/v1/words/oclock").andExpect {
            status { isOk() }
            jsonPath("$.lemma") { value("o'clock") }
        }
    }

    @Test
    fun `unknown word is a 404 problem response`() {
        mockMvc.get("/api/v1/words/nope").andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("No word 'nope' found") }
        }
    }

    @Test
    fun `search by prefix and by category`() {
        mockMvc.get("/api/v1/words?q=D").andExpect {
            status { isOk() }
            jsonPath("$.totalItems") { value(1) }
            jsonPath("$.items[0].slug") { value("dog") }
            jsonPath("$.items[0].partsOfSpeech") { value(contains("noun", "verb")) }
        }
        mockMvc.get("/api/v1/words?category=feelings").andExpect {
            jsonPath("$.items[*].slug") { value(contains("joy")) }
        }
        mockMvc.get("/api/v1/words?page=0&size=2").andExpect {
            jsonPath("$.items[*].slug") { value(contains("dog", "happy")) }
            jsonPath("$.totalPages") { value(3) }
        }
    }

    @Test
    fun `page size is capped`() {
        mockMvc.get("/api/v1/words?size=500").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `categories report word counts`() {
        mockMvc.get("/api/v1/categories").andExpect {
            status { isOk() }
            jsonPath("$[?(@.slug == 'feelings')].wordCount") { value(contains(1)) }
            jsonPath("$[?(@.slug == 'shapes')].wordCount") { value(contains(0)) }
        }
    }

    @Test
    fun `random word respects category`() {
        mockMvc.get("/api/v1/random-word?category=feelings").andExpect {
            status { isOk() }
            jsonPath("$.slug") { value("joy") }
        }
        mockMvc.get("/api/v1/random-word?category=shapes").andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/random-word").andExpect { status { isOk() } }
    }

    @Test
    fun `openapi spec is published`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/v1/words/{slug}']") { exists() }
        }
    }

    @Test
    fun `frequency import ranks catalog words and marks the rest rare`() {
        // dog, joy, happy are in the list (the first "Dog" line wins); o'clock and zonk are not.
        assertEquals(mapOf(Difficulty.EVERYDAY to 3, Difficulty.RARE to 2), levelCounts)
        mockMvc.get("/api/v1/words/dog").andExpect { jsonPath("$.difficulty") { value(1) } }
        mockMvc.get("/api/v1/words/oclock").andExpect { jsonPath("$.difficulty") { value(5) } }
    }

    @Test
    fun `levels report word counts`() {
        mockMvc.get("/api/v1/levels").andExpect {
            status { isOk() }
            jsonPath("$[*].level") { value(contains(1, 2, 3, 4, 5)) }
            jsonPath("$[0].name") { value("Everyday") }
            jsonPath("$[*].wordCount") { value(contains(3, 0, 0, 0, 2)) }
        }
    }

    @Test
    fun `filters combine prefix, category and difficulty`() {
        mockMvc.get("/api/v1/words?difficulty=5").andExpect {
            jsonPath("$.items[*].slug") { value(contains("oclock", "zonk")) } // search still finds sensitive words
        }
        mockMvc.get("/api/v1/words?difficulty=1&category=feelings").andExpect {
            jsonPath("$.items[*].slug") { value(contains("joy")) }
        }
        mockMvc.get("/api/v1/words?difficulty=1&q=h").andExpect {
            jsonPath("$.items[*].slug") { value(contains("happy")) }
        }
        repeat(5) { // zonk is level 5 too, but sensitive words are never picked at random
            mockMvc.get("/api/v1/random-word?difficulty=5").andExpect { jsonPath("$.slug") { value("oclock") } }
        }
        mockMvc.get("/api/v1/random-word?difficulty=3").andExpect { status { isNotFound() } }
    }

    @Test
    fun `out of range difficulty is a bad request`() {
        mockMvc.get("/api/v1/words?difficulty=9").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `labelled slurs and obscenities are marked sensitive`() {
        assertEquals(listOf("zonk"), jdbc.queryForList("SELECT slug FROM word WHERE sensitive = TRUE", String::class.java))
    }
}
