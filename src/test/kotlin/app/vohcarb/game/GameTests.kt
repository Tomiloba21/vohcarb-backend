package app.vohcarb.game

import app.vohcarb.catalog.importer.FrequencyImporter
import app.vohcarb.catalog.importer.WordNetImporter
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: ObjectMapper,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val wordNet: WordNetImporter,
    @Autowired private val frequency: FrequencyImporter,
) {

    @BeforeAll
    fun catalog() = ensureFixtureCatalog(jdbc, wordNet, frequency)

    private data class User(val token: String, val id: Long)

    private fun newUser(name: String): User {
        val response = mockMvc.post("/api/v1/auth/dev-login") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("email" to "$name-${UUID.randomUUID()}@example.com", "displayName" to name))
        }.andReturn().response.contentAsString
        val tree = json.readTree(response)
        return User(tree["accessToken"].asString(), tree["user"]["id"].asLong())
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
    private fun User.put(path: String, body: Any) = mockMvc.put(path) {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun group(owner: User, vararg others: User): Long {
        val created = owner.post("/api/v1/groups", mapOf("name" to "Game night")).andExpect { status { isCreated() } }.body()
        others.forEach { it.post("/api/v1/groups/join", mapOf("code" to created["inviteCode"].asString())).andExpect { status { isOk() } } }
        return created["id"].asLong()
    }

    private fun User.start(challenge: Challenge, group: Long? = null): JsonNode =
        post("/api/v1/games/daily/$challenge/start" + (group?.let { "?group=$it" } ?: "")).andExpect { status { isOk() } }.body()

    private fun User.submit(playId: Long, body: Any): JsonNode = post("/api/v1/games/plays/$playId/submit", body).andExpect { status { isOk() } }.body()

    @Test
    fun `the overview lists today's five challenges, and starting one gives its content and a deadline`() {
        val ada = newUser("Ada")
        val overview = ada.get("/api/v1/games/daily").andExpect { status { isOk() } }.body()
        assertEquals(Challenge.entries.map { it.name }, overview["challenges"].toList().map { it["challenge"].asString() })
        assertTrue(overview["challenges"].all { it["status"].asString() == "NOT_STARTED" })

        val play = ada.start(Challenge.NAME_PLACE_ANIMAL_THING)
        assertEquals(NpatRules.ROUNDS, play["letters"].size())
        assertTrue(play["deadline"].asString() > play["startedAt"].asString())

        // Starting again resumes the same play
        assertEquals(play["playId"].asLong(), ada.start(Challenge.NAME_PLACE_ANIMAL_THING)["playId"].asLong())
        assertEquals("IN_PROGRESS", ada.get("/api/v1/games/daily").body()["challenges"][0]["status"].asString())
    }

    @Test
    fun `name-place-animal-thing scores unique answers 10 and shared 5, and group votes decide unlisted ones`() {
        val ada = newUser("Ada")
        val bo = newUser("Bo")
        val groupId = group(ada, bo)
        val adaPlay = ada.start(Challenge.NAME_PLACE_ANIMAL_THING, groupId)
        val boPlay = bo.start(Challenge.NAME_PLACE_ANIMAL_THING, groupId)
        // Same group, same day: same letters
        assertEquals(adaPlay["letters"], boPlay["letters"])
        val letter = adaPlay["letters"][0].asString().lowercase()
        val listedName = "${letter}ola${UUID.randomUUID().toString().filter(Char::isLetter).take(6)}"
        jdbc.update("INSERT INTO game_name (normalized, display) VALUES (?, ?)", listedName, listedName)

        // Both give the same listed name (shared: 5 each) and different places nobody has listed
        ada.submit(adaPlay["playId"].asLong(), mapOf("rounds" to listOf(mapOf("name" to listedName, "place" to "${letter}adaville", "animal" to "${letter}qqq"))))
        val boResult = bo.submit(boPlay["playId"].asLong(), mapOf("rounds" to listOf(mapOf("name" to listedName.uppercase(), "place" to "${letter}boburg"))))
        assertEquals(15, boResult["score"].asInt()) // 5 shared name + 10 unique unlisted place that stands

        // Bo sees Ada's unlisted place and rejects it
        val review = bo.get("/api/v1/games/daily/review?group=$groupId").andExpect { status { isOk() } }.body()
        val adaPlace = review.single { it["answer"].asString() == "${letter}adaville" }
        assertEquals(letter.uppercase(), adaPlace["letter"].asString())
        bo.put("/api/v1/games/answers/${adaPlace["answerId"].asLong()}/vote", mapOf("accepted" to false)).andExpect { status { isNoContent() } }

        val adaResult = ada.get("/api/v1/games/plays/${adaPlay["playId"].asLong()}").andExpect { status { isOk() } }.body()
        assertEquals(5, adaResult["score"].asInt())
        val place = adaResult["answers"].single { it["category"].asString() == "PLACE" }
        assertEquals("UNLISTED", place["verdict"].asString())
        assertFalse(place["stands"].asBoolean())
        assertEquals("INVALID", adaResult["answers"].single { it["category"].asString() == "ANIMAL" }["verdict"].asString())

        // You can't vote on your own answers
        val bosPlace = ada.get("/api/v1/games/daily/review?group=$groupId").body().single()
        bo.put("/api/v1/games/answers/${bosPlace["answerId"].asLong()}/vote", mapOf("accepted" to true)).andExpect { status { isBadRequest() } }

        val board = ada.get("/api/v1/games/daily/NAME_PLACE_ANIMAL_THING/leaderboard?group=$groupId").andExpect { status { isOk() } }.body()
        assertEquals(listOf(15, 5), board["rows"].toList().map { it["score"].asInt() })
        assertTrue(board["rows"][1]["isMe"].asBoolean())
    }

    @Test
    fun `complete-the-word hides the answers, charges for revealed letters and checks answers`() {
        val ada = newUser("Ada")
        val play = ada.start(Challenge.COMPLETE_THE_WORD_EASY)
        val playId = play["playId"].asLong()
        val first = play["words"][0]
        assertFalse(play.toString().contains("\"word\""), "the answers must not be sent")
        assertTrue(first["mask"].asString().contains('_'))

        // Reveal until the whole word shows, then answer it
        var mask = first["mask"].asString()
        var reveals = 0
        while ('_' in mask) {
            mask = ada.post("/api/v1/games/plays/$playId/reveal", mapOf("index" to 0)).andExpect { status { isOk() } }.body()["mask"].asString()
            reveals++
        }
        val result = ada.submit(playId, mapOf("answers" to listOf(mapOf("index" to 0, "answer" to mask, "elapsedMs" to 0), mapOf("index" to 1, "answer" to "wrong"))))
        val answers = result["answers"]
        assertEquals("VALID", answers[0]["verdict"].asString())
        assertEquals(mask, answers[0]["correctAnswer"].asString())
        assertEquals(maxOf(0, 15 - 3 * reveals), answers[0]["points"].asInt())
        assertEquals("INVALID", answers[1]["verdict"].asString())

        // Once a day
        ada.post("/api/v1/games/daily/COMPLETE_THE_WORD_EASY/start").andExpect { status { isConflict() } }
        ada.post("/api/v1/games/plays/$playId/reveal", mapOf("index" to 0)).andExpect { status { isConflict() } }
    }

    @Test
    fun `category sprint counts distinct words in the category by their level`() {
        val ada = newUser("Ada")
        val play = ada.start(Challenge.CATEGORY_SPRINT)
        val result = ada.submit(play["playId"].asLong(), mapOf("words" to listOf("dog", "Dog ", "happy", "joy", "ox", "notaword")))
        val answers = result["answers"]
        assertEquals(5, answers.size()) // "Dog " is a repeat of "dog"
        assertEquals("INVALID", answers.single { it["answer"].asString() == "notaword" }["verdict"].asString())
        assertEquals(answers.sumOf { it["points"].asInt() }, result["score"].asInt())
        assertTrue(answers.filter { it["verdict"].asString() == "VALID" }.all { it["points"].asInt() in 1..5 })
    }

    @Test
    fun `answers after the deadline don't count`() {
        val ada = newUser("Ada")
        val play = ada.start(Challenge.CATEGORY_SPRINT)
        jdbc.update("UPDATE game_play SET started_at = started_at - INTERVAL '1' HOUR WHERE id = ?", play["playId"].asLong())
        val result = ada.submit(play["playId"].asLong(), mapOf("words" to listOf("dog")))
        assertTrue(result["late"].asBoolean())
        assertEquals(0, result["score"].asInt())
        assertEquals(0, result["answers"].size())
    }

    @Test
    fun `group challenges are for members only, and deleting a group deletes its game data`() {
        val ada = newUser("Ada")
        val stranger = newUser("Eve")
        val groupId = group(ada)
        stranger.get("/api/v1/games/daily?group=$groupId").andExpect { status { isNotFound() } }
        stranger.post("/api/v1/games/daily/CATEGORY_SPRINT/start?group=$groupId").andExpect { status { isNotFound() } }

        val play = ada.start(Challenge.CATEGORY_SPRINT, groupId)
        ada.submit(play["playId"].asLong(), mapOf("words" to listOf("dog")))
        stranger.get("/api/v1/games/plays/${play["playId"].asLong()}").andExpect { status { isNotFound() } }

        ada.delete("/api/v1/groups/$groupId").andExpect { status { isNoContent() } }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM game_play WHERE group_id = ?", Int::class.java, groupId))
    }
}
