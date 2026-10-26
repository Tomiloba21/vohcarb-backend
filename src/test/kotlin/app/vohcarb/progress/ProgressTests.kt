package app.vohcarb.progress

import app.vohcarb.catalog.importer.FrequencyImporter
import app.vohcarb.catalog.importer.WordNetImporter
import app.vohcarb.devLogin
import app.vohcarb.ensureFixtureCatalog
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.emptyIterable
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: ObjectMapper,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val wordNet: WordNetImporter,
    @Autowired private val frequency: FrequencyImporter,
) {

    @BeforeAll
    fun catalog() = ensureFixtureCatalog(jdbc, wordNet, frequency)

    /** A fresh user per test keeps tests independent. */
    private fun newUser() = mockMvc.devLogin(json, "user-${UUID.randomUUID()}@example.com")

    private fun preferences(token: String, min: Int, max: Int, categories: List<String> = emptyList()) {
        mockMvc.put("/api/v1/me/preferences") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(
                mapOf(
                    "minDifficulty" to min, "maxDifficulty" to max, "wordsPerDay" to 10,
                    "timezone" to "UTC", "categories" to categories, "reminderTimes" to emptyList<String>(),
                ),
            )
        }.andExpect { status { isOk() } }
    }

    private fun feed(token: String, query: String = "") =
        mockMvc.get("/api/v1/me/feed$query") { header("Authorization", "Bearer $token") }

    private fun patch(token: String, slug: String, body: String) = mockMvc.patch("/api/v1/me/words/$slug") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun sync(token: String, since: Long, vararg changes: Map<String, Any>): JsonNode {
        val response = mockMvc.post("/api/v1/me/sync") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("sinceRevision" to since, "changes" to changes.toList()))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readTree(response)
    }

    private fun change(word: String, saved: Boolean, at: Instant) =
        mapOf("word" to word, "seen" to true, "liked" to false, "saved" to saved, "known" to false, "changedAt" to at.toString())

    private fun JsonNode.strings(pick: (JsonNode) -> JsonNode) = (0 until size()).map { pick(get(it)).asString() }

    private fun ResultActionsDsl.slugs(vararg expected: String) = andExpect {
        status { isOk() }
        if (expected.isEmpty()) jsonPath("$[*].slug") { value(emptyIterable<Any>()) }
        else jsonPath("$[*].slug") { value(containsInAnyOrder(*expected)) }
    }

    @Test
    fun `feed needs a token`() {
        mockMvc.get("/api/v1/me/feed").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `feed follows the level range and categories`() {
        val token = newUser()
        feed(token).slugs() // defaults are levels 2–4, and the fixture only has levels 1 and 5
        preferences(token, 1, 5)
        feed(token).slugs("dog", "happy", "joy", "oclock") // never zonk: it's sensitive
        preferences(token, 5, 5)
        feed(token).slugs("oclock")
        preferences(token, 1, 5, listOf("feelings", "movement"))
        feed(token).slugs("dog", "joy")
    }

    @Test
    fun `seen and known words leave the feed`() {
        val token = newUser()
        preferences(token, 1, 1)
        patch(token, "dog", """{"seen": true}""").andExpect { status { isOk() } }
        patch(token, "joy", """{"known": true}""").andExpect { status { isOk() } }
        feed(token).slugs("happy")
        patch(token, "happy", """{"saved": true}""") // saving alone doesn't remove it
        feed(token).slugs("happy")
    }

    @Test
    fun `feed size can be chosen`() {
        val token = newUser()
        preferences(token, 1, 5)
        feed(token, "?size=2").andExpect { jsonPath("$.length()") { value(2) } }
        feed(token, "?size=99").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `patch changes only the given flags`() {
        val token = newUser()
        patch(token, "dog", """{"saved": true}""").andExpect {
            jsonPath("$.word.slug") { value("dog") }
            jsonPath("$.saved") { value(true) }
            jsonPath("$.liked") { value(false) }
        }
        patch(token, "dog", """{"liked": true}""").andExpect {
            jsonPath("$.saved") { value(true) }
            jsonPath("$.liked") { value(true) }
        }
        mockMvc.get("/api/v1/me/words/happy") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.saved") { value(false) }
            jsonPath("$.changedAt") { value(null as Any?) }
        }
        patch(token, "nope", """{"saved": true}""").andExpect { status { isNotFound() } }
    }

    @Test
    fun `lists show saved and liked words`() {
        val token = newUser()
        patch(token, "dog", """{"saved": true}""")
        patch(token, "happy", """{"liked": true}""")
        patch(token, "joy", """{"saved": true}""")
        mockMvc.get("/api/v1/me/words") { header("Authorization", "Bearer $token") }.andExpect {
            jsonPath("$.items[*].word.slug") { value(contains("joy", "dog")) } // most recent first
        }
        mockMvc.get("/api/v1/me/words?list=LIKED") { header("Authorization", "Bearer $token") }.andExpect {
            jsonPath("$.items[*].word.slug") { value(contains("happy")) }
        }
        mockMvc.get("/api/v1/me/words?list=bogus") { header("Authorization", "Bearer $token") }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `sync pushes, pulls and lets the later change win`() {
        val token = newUser()
        val t0 = Instant.now().minus(Duration.ofHours(3))
        val t1 = t0.plus(Duration.ofHours(1))
        val t2 = t0.plus(Duration.ofHours(2))

        // Phone saves "dog" offline, plus a word the server doesn't have.
        val phone = sync(token, 0, change("dog", saved = true, at = t1), change("nope", saved = true, at = t1))
        assertEquals(1, phone["revision"].asLong())
        assertEquals(listOf("dog"), phone["changes"].strings { it["word"] })
        assertEquals(listOf("nope"), phone["unknownWords"].strings { it })

        // Tablet syncs for the first time and gets it.
        val tablet = sync(token, 0)
        assertTrue(tablet["changes"][0]["saved"].asBoolean())

        // Tablet had un-saved it earlier (t0, older): ignored.
        val stale = sync(token, 1, change("dog", saved = false, at = t0))
        assertEquals(1, stale["revision"].asLong())
        assertEquals(0, stale["changes"].size())

        // Tablet un-saves it later (t2): wins, and the phone picks it up.
        assertEquals(2, sync(token, 1, change("dog", saved = false, at = t2))["revision"].asLong())
        val phoneAgain = sync(token, 1)
        assertEquals(false, phoneAgain["changes"][0]["saved"].asBoolean())
        assertEquals(false, phoneAgain["hasMore"].asBoolean())
    }

    @Test
    fun `a device clock in the future is capped to server time`() {
        val token = newUser()
        val response = sync(token, 0, change("joy", saved = true, at = Instant.now().plus(Duration.ofDays(1))))
        val stored = Instant.parse(response["changes"][0]["changedAt"].asString())
        assertTrue(!stored.isAfter(Instant.now()))
    }

    @Test
    fun `catalog can't be wiped while users have words`() {
        val token = newUser()
        patch(token, "dog", """{"saved": true}""").andExpect { status { isOk() } }
        assertFailsWith<IllegalStateException> {
            WordNetImporter::class.java.getResourceAsStream("/wordnet-fixture.xml")!!.use { wordNet.importStream(it, replace = true) }
        }
    }
}
