package app.vohcarb.social

import app.vohcarb.catalog.importer.FrequencyImporter
import app.vohcarb.catalog.importer.WordNetImporter
import app.vohcarb.ensureFixtureCatalog
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.not
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: ObjectMapper,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val wordNet: WordNetImporter,
    @Autowired private val frequency: FrequencyImporter,
) {

    @BeforeAll
    fun catalog() = ensureFixtureCatalog(jdbc, wordNet, frequency)

    private data class User(val token: String, val id: Long, val name: String)

    private fun newUser(name: String): User {
        val response = mockMvc.post("/api/v1/auth/dev-login") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("email" to "$name-${UUID.randomUUID()}@example.com", "displayName" to name))
        }.andReturn().response.contentAsString
        val tree = json.readTree(response)
        return User(tree["accessToken"].asString(), tree["user"]["id"].asLong(), name)
    }

    private fun User.get(path: String) = mockMvc.get(path) { header("Authorization", "Bearer $token") }
    private fun User.delete(path: String) = mockMvc.delete(path) { header("Authorization", "Bearer $token") }
    private fun User.post(path: String, body: Any? = null) = mockMvc.post(path) {
        header("Authorization", "Bearer $token")
        if (body != null) {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(body)
        }
    }

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    /** Owner creates a group; returns (group id, invite code). */
    private fun User.createGroup(name: String = "Book club"): Pair<Long, String> {
        val group = post("/api/v1/groups", mapOf("name" to name)).andExpect { status { isCreated() } }.body()
        return group["id"].asLong() to group["inviteCode"].asString()
    }

    private fun User.join(code: String) = post("/api/v1/groups/join", mapOf("code" to code))

    @Test
    fun `create a group and join it with the invite code`() {
        val ada = newUser("Ada")
        val bola = newUser("Bola")
        val (id, code) = ada.createGroup()
        assertEquals(8, code.length)

        // Codes are forgiving: lower case and dashes are fine.
        val typed = code.lowercase().chunked(4).joinToString("-")
        bola.join(typed).andExpect {
            status { isOk() }
            jsonPath("$.myRole") { value("MEMBER") }
            jsonPath("$.members[*].displayName") { value(contains("Ada", "Bola")) }
            jsonPath("$.members[0].email") { doesNotExist() }
        }
        bola.join(code).andExpect { status { isOk() } } // joining twice is harmless
        ada.get("/api/v1/groups").andExpect {
            jsonPath("$[?(@.id == $id)].memberCount") { value(contains(2)) }
            jsonPath("$[?(@.id == $id)].myRole") { value(contains("OWNER")) }
        }
    }

    @Test
    fun `outsiders can't see a group or join with a wrong code`() {
        val ada = newUser("Ada")
        val eve = newUser("Eve")
        val (id, _) = ada.createGroup()
        eve.get("/api/v1/groups/$id").andExpect { status { isNotFound() } }
        eve.get("/api/v1/groups/$id/words").andExpect { status { isNotFound() } }
        eve.get("/api/v1/groups/$id/leaderboard").andExpect { status { isNotFound() } }
        eve.join("WRONGCODE").andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/groups").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `owner can remove members and replace the invite code`() {
        val ada = newUser("Ada")
        val bola = newUser("Bola")
        val chidi = newUser("Chidi")
        val (id, code) = ada.createGroup()
        bola.join(code)

        bola.delete("/api/v1/groups/$id/members/${ada.id}").andExpect { status { isForbidden() } }
        ada.delete("/api/v1/groups/$id/members/${bola.id}").andExpect { status { isNoContent() } }
        bola.get("/api/v1/groups/$id").andExpect { status { isNotFound() } }

        val newCode = ada.post("/api/v1/groups/$id/invite-code").andExpect { status { isOk() } }.body()["inviteCode"].asString()
        assertNotEquals(code, newCode)
        chidi.join(code).andExpect { status { isNotFound() } }
        chidi.join(newCode).andExpect { status { isOk() } }
    }

    @Test
    fun `owner leaving hands the group on, last one out deletes it`() {
        val ada = newUser("Ada")
        val bola = newUser("Bola")
        val (id, code) = ada.createGroup()
        bola.join(code)

        ada.post("/api/v1/groups/$id/leave").andExpect { status { isNoContent() } }
        bola.get("/api/v1/groups/$id").andExpect { jsonPath("$.myRole") { value("OWNER") } }

        bola.post("/api/v1/groups/$id/words", mapOf("word" to "dog"))
        bola.post("/api/v1/groups/$id/leave").andExpect { status { isNoContent() } }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM friend_group WHERE id = ?", Long::class.java, id))
    }

    @Test
    fun `shared word list with notes and removal rules`() {
        val ada = newUser("Ada")
        val bola = newUser("Bola")
        val (id, code) = ada.createGroup()
        bola.join(code)

        bola.post("/api/v1/groups/$id/words", mapOf("word" to "joy", "note" to "from the poem we read")).andExpect {
            status { isOk() }
            jsonPath("$.addedBy.displayName") { value("Bola") }
            jsonPath("$.note") { value("from the poem we read") }
        }
        ada.post("/api/v1/groups/$id/words", mapOf("word" to "dog"))
        ada.post("/api/v1/groups/$id/words", mapOf("word" to "joy")).andExpect {
            jsonPath("$.addedBy.displayName") { value("Bola") } // already there: the existing entry comes back
        }
        ada.post("/api/v1/groups/$id/words", mapOf("word" to "nope")).andExpect { status { isNotFound() } }

        bola.get("/api/v1/groups/$id/words").andExpect {
            jsonPath("$.items[*].word.slug") { value(contains("dog", "joy")) } // newest first
        }

        bola.delete("/api/v1/groups/$id/words/dog").andExpect { status { isForbidden() } } // Ada added it
        bola.delete("/api/v1/groups/$id/words/joy").andExpect { status { isNoContent() } }
        ada.post("/api/v1/groups/$id/words", mapOf("word" to "joy"))
        ada.delete("/api/v1/groups/$id/words/joy").andExpect { status { isNoContent() } } // owner can remove any
        ada.get("/api/v1/groups/$id/words").andExpect {
            jsonPath("$.items[*].word.slug") { value(contains("dog")) }
        }
    }

    @Test
    fun `word of the day is stable, shared by members and never sensitive`() {
        val ada = newUser("Ada")
        val bola = newUser("Bola")
        val (id, code) = ada.createGroup()
        bola.join(code)
        val forAda = ada.get("/api/v1/groups/$id/word-of-the-day").andExpect { status { isOk() } }.body()
        val forBola = bola.get("/api/v1/groups/$id/word-of-the-day").body()
        assertEquals(forAda["word"]["slug"], forBola["word"]["slug"])

        // Public word of the day: no login, same answer for the same date, and over a month never "zonk".
        val first = mockMvc.get("/api/v1/word-of-the-day?date=2026-01-01").andExpect { status { isOk() } }.body()
        assertEquals(first["word"]["slug"], mockMvc.get("/api/v1/word-of-the-day?date=2026-01-01").body()["word"]["slug"])
        for (day in 1..31) {
            mockMvc.get("/api/v1/word-of-the-day?date=2026-01-%02d".format(day)).andExpect {
                jsonPath("$.word.slug") { value(not("zonk")) }
            }
        }
    }

    @Test
    fun `leaderboard ranks members by reviews, then streak`() {
        val ada = newUser("Ada")
        val bola = newUser("Bola")
        val chidi = newUser("Chidi")
        val (id, code) = ada.createGroup()
        bola.join(code)
        chidi.join(code)

        val now = Instant.now()
        fun User.review(vararg times: Instant) = post(
            "/api/v1/me/practice/reviews",
            mapOf("reviews" to times.mapIndexed { i, t -> mapOf("word" to listOf("dog", "joy", "happy")[i % 3], "grade" to "GOOD", "reviewedAt" to t.toString()) }),
        ).andExpect { status { isOk() } }

        bola.review(now, now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(2))) // 3 reviews, 3-day streak
        ada.review(now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(4)).minus(Duration.ofDays(20)), now)
        // Ada: 2 reviews this week (one is 20 days old), 1-day streak

        ada.get("/api/v1/groups/$id/leaderboard").andExpect {
            status { isOk() }
            jsonPath("$.days") { value(7) }
            jsonPath("$.rows[*].displayName") { value(contains("Bola", "Ada", "Chidi")) }
            jsonPath("$.rows[*].reviews") { value(contains(3, 2, 0)) }
            jsonPath("$.rows[*].rank") { value(contains(1, 2, 3)) }
            jsonPath("$.rows[0].streakDays") { value(3) }
            jsonPath("$.rows[?(@.isMe == true)].displayName") { value(contains("Ada")) }
        }
        ada.get("/api/v1/groups/$id/leaderboard?days=30").andExpect {
            jsonPath("$.rows[*].reviews") { value(containsInAnyOrder(3, 3, 0)) }
            jsonPath("$.rows[0].rank") { value(1) }
            jsonPath("$.rows[1].rank") { value(2) } // same reviews, Bola's longer streak wins
        }
    }

    @Test
    fun `invalid input is rejected`() {
        val ada = newUser("Ada")
        ada.post("/api/v1/groups", mapOf("name" to "")).andExpect { status { isBadRequest() } }
        ada.post("/api/v1/groups", mapOf("name" to "x".repeat(65))).andExpect { status { isBadRequest() } }
        val (id, _) = ada.createGroup()
        ada.get("/api/v1/groups/$id/leaderboard?days=90").andExpect { status { isBadRequest() } }
        ada.post("/api/v1/groups/$id/words", mapOf("word" to "dog", "note" to "n".repeat(281))).andExpect { status { isBadRequest() } }
        ada.delete("/api/v1/groups/$id/members/${ada.id}").andExpect { status { isBadRequest() } }
        assertTrue(GroupService.normalize(" ab cd-ef gh ") == "ABCDEFGH")
    }
}
