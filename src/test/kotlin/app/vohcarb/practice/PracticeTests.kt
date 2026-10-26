package app.vohcarb.practice

import app.vohcarb.catalog.importer.FrequencyImporter
import app.vohcarb.catalog.importer.WordNetImporter
import app.vohcarb.devLogin
import app.vohcarb.ensureFixtureCatalog
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PracticeTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: ObjectMapper,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val wordNet: WordNetImporter,
    @Autowired private val frequency: FrequencyImporter,
) {

    @BeforeAll
    fun catalog() = ensureFixtureCatalog(jdbc, wordNet, frequency)

    private fun newUser() = mockMvc.devLogin(json, "practice-${UUID.randomUUID()}@example.com")

    private fun read(response: String): JsonNode = json.readTree(response)

    private fun save(token: String, slug: String, known: Boolean = false) {
        mockMvc.patch("/api/v1/me/words/$slug") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"saved": true, "known": $known}"""
        }.andExpect { status { isOk() } }
    }

    private fun session(token: String): JsonNode = read(
        mockMvc.get("/api/v1/me/practice/session") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun stats(token: String): JsonNode = read(
        mockMvc.get("/api/v1/me/practice/stats") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun review(token: String, vararg reviews: Triple<String, String, Instant>): JsonNode = read(
        mockMvc.post("/api/v1/me/practice/reviews") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(
                mapOf("reviews" to reviews.map { (word, grade, at) -> mapOf("word" to word, "grade" to grade, "reviewedAt" to at.toString()) }),
            )
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun JsonNode.slugs() = (0 until this["items"].size()).map { this["items"][it]["word"]["slug"].asString() }

    @Test
    fun `practice needs a token`() {
        mockMvc.get("/api/v1/me/practice/session").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `saved words show up as new, known ones don't`() {
        val token = newUser()
        assertEquals(0, session(token)["items"].size())

        save(token, "dog")
        save(token, "joy")
        save(token, "happy", known = true)
        val session = session(token)
        assertEquals(2, session["newWords"].asLong())
        assertEquals(0, session["reviewsDue"].asLong())
        assertEquals(listOf("joy", "dog"), session.slugs()) // most recently saved first
        assertTrue(session["items"][0]["card"].isNull)
    }

    @Test
    fun `every item has a multiple-choice question with the right answer marked`() {
        val token = newUser()
        save(token, "dog")
        val item = session(token)["items"][0]
        val question = item["question"]
        val options = (0 until question["options"].size()).map { question["options"][it].asString() }
        assertEquals(4, options.toSet().size)
        val answer = options[question["correctIndex"].asInt()]
        when (question["type"].asString()) {
            "MEANING_OF_WORD" -> {
                assertEquals("dog", question["prompt"].asString())
                assertEquals(item["word"]["senses"][0]["definition"].asString(), answer)
            }
            "WORD_FOR_MEANING" -> assertEquals("dog", answer)
            else -> error("unexpected type $question")
        }
    }

    @Test
    fun `a good answer schedules the word for tomorrow`() {
        val token = newUser()
        save(token, "dog")
        val result = review(token, Triple("dog", "GOOD", Instant.now()))
        assertEquals(1, result["applied"].asInt())
        val card = result["cards"][0]
        assertEquals(1, card["intervalDays"].asInt())
        val due = Instant.parse(card["dueAt"].asString())
        assertTrue(due.isAfter(Instant.now().plus(Duration.ofHours(23))))

        assertEquals(0, session(token)["items"].size()) // nothing due until tomorrow
        val stats = stats(token)
        assertEquals(1, stats["totalCards"].asLong())
        assertEquals(1, stats["reviewedToday"].asInt())
        assertEquals(1, stats["streakDays"].asInt())
    }

    @Test
    fun `again brings the word back in ten minutes`() {
        val token = newUser()
        save(token, "dog")
        val card = review(token, Triple("dog", "AGAIN", Instant.now()))["cards"][0]
        val due = Instant.parse(card["dueAt"].asString())
        assertTrue(due.isBefore(Instant.now().plus(Duration.ofMinutes(11))))
    }

    @Test
    fun `overdue reviews come before new words`() {
        val token = newUser()
        save(token, "dog")
        save(token, "joy")
        // Answered offline three days ago: due again two days ago.
        review(token, Triple("dog", "GOOD", Instant.now().minus(Duration.ofDays(3))))
        val session = session(token)
        assertEquals(1, session["reviewsDue"].asLong())
        assertEquals(1, session["newWords"].asLong())
        assertEquals(listOf("dog", "joy"), session.slugs())
        assertEquals(1, session["items"][0]["card"]["repetitions"].asInt())
    }

    @Test
    fun `re-sent and out-of-order reviews don't move the card`() {
        val token = newUser()
        save(token, "dog")
        val at = Instant.now().minus(Duration.ofHours(2))
        review(token, Triple("dog", "GOOD", at))
        val again = review(token, Triple("dog", "GOOD", at), Triple("dog", "EASY", at.minus(Duration.ofHours(1))))
        assertEquals(0, again["applied"].asInt())
        assertEquals(2, again["ignored"].asInt())
        assertEquals(1, again["cards"][0]["repetitions"].asInt())
    }

    @Test
    fun `unknown words are reported`() {
        val token = newUser()
        val result = review(token, Triple("nope", "GOOD", Instant.now()))
        assertEquals("nope", result["unknownWords"][0].asString())
        assertEquals(0, result["applied"].asInt())
    }

    @Test
    fun `streak counts consecutive days`() {
        val token = newUser()
        val now = Instant.now()
        review(
            token,
            Triple("dog", "GOOD", now.minus(Duration.ofDays(1))),
            Triple("joy", "GOOD", now.minus(Duration.ofDays(2))),
            Triple("happy", "GOOD", now.minus(Duration.ofDays(4))), // gap on day 3
        )
        val beforeToday = stats(token)
        assertEquals(2, beforeToday["streakDays"].asInt()) // still alive: yesterday counts
        assertEquals(0, beforeToday["reviewedToday"].asInt())

        review(token, Triple("oclock", "GOOD", now))
        assertEquals(3, stats(token)["streakDays"].asInt())
    }
}
