package app.vohcarb.game.live

import app.vohcarb.catalog.importer.FrequencyImporter
import app.vohcarb.catalog.importer.WordNetImporter
import app.vohcarb.ensureFixtureCatalog
import app.vohcarb.game.Challenge
import app.vohcarb.game.NpatRoundAnswers
import app.vohcarb.game.Verdict
import app.vohcarb.social.GroupService
import app.vohcarb.user.UserService
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveRoomsTest(
    @Autowired private val rooms: LiveRooms,
    @Autowired private val users: UserService,
    @Autowired private val groups: GroupService,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val wordNet: WordNetImporter,
    @Autowired private val frequency: FrequencyImporter,
) {

    @BeforeAll
    fun catalog() = ensureFixtureCatalog(jdbc, wordNet, frequency)

    private fun user(name: String): Long {
        val email = "$name-${UUID.randomUUID()}@example.com"
        return users.signIn("dev", email, email, name).id!!
    }

    /** Waits for the room to reach a state (timers run on their own threads). */
    private fun awaitState(roomId: String, userId: Long, what: String, test: (RoomStateDto) -> Boolean): RoomStateDto {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            rooms.state(roomId, userId)?.let { if (test(it)) return it }
            Thread.sleep(20)
        }
        error("Room never reached: $what (now ${rooms.state(roomId, userId)?.phase})")
    }

    private fun statusOf(block: () -> Unit) = assertThrows<ResponseStatusException> { block() }.statusCode

    @Test
    fun `in a friends match only the host starts, shared answers score 5, rejected unlisted names score nothing, results are saved`() {
        val ada = user("Ada")
        val bo = user("Bo")
        val room = rooms.create(ada, CreateRoomRequest(Challenge.NAME_PLACE_ANIMAL_THING, RoomKind.FRIENDS))
        assertEquals(6, room.code.length)
        rooms.joinByCode(bo, room.code.lowercase())
        assertTrue(rooms.connected(room.id, ada))
        assertTrue(rooms.connected(room.id, bo))

        assertEquals(HttpStatus.FORBIDDEN, statusOf { rooms.handle(room.id, bo, ClientMessage("start")) })
        rooms.handle(room.id, ada, ClientMessage("start"))
        val round = awaitState(room.id, ada, "round 1") { it.phase == RoomPhase.ROUND && it.round == 0 }
        val letter = round.letter!!.lowercase()
        val name = "${letter}ola${UUID.randomUUID().toString().filter(Char::isLetter).take(5)}"
        jdbc.update("INSERT INTO game_name (normalized, display) VALUES (?, ?)", name, name)

        rooms.handle(room.id, ada, ClientMessage("npat", round = 0, answers = NpatRoundAnswers(name = name, place = "${letter}adaville")))
        assertTrue(rooms.state(room.id, ada)!!.youSubmitted)
        rooms.handle(room.id, bo, ClientMessage("npat", round = 0, answers = NpatRoundAnswers(name = name.uppercase())))

        // Both in: the round ends at once, and Ada's unlisted place goes to a vote
        val voting = awaitState(room.id, bo, "voting") { it.phase == RoomPhase.VOTING }
        val item = voting.votes!!.single()
        assertEquals("${letter}adaville", item.answer)
        rooms.handle(room.id, bo, ClientMessage("vote", key = item.key, accepted = false))

        val results = awaitState(room.id, ada, "results") { it.phase == RoomPhase.RESULTS }
        val adas = results.results!!.single { it.userId == ada }
        assertEquals(5, adas.roundPoints) // shared name 5, rejected place 0
        assertEquals(false, adas.answers.single { it.category == "PLACE" }.stands)
        assertEquals(Verdict.UNLISTED, adas.answers.single { it.category == "PLACE" }.verdict)

        // The other rounds: both hand in nothing, so each ends straight away
        for (r in 1 until results.rounds) {
            awaitState(room.id, ada, "round ${r + 1}") { it.phase == RoomPhase.ROUND && it.round == r }
            rooms.handle(room.id, ada, ClientMessage("npat", round = r))
            rooms.handle(room.id, bo, ClientMessage("npat", round = r))
        }
        val finished = awaitState(room.id, ada, "finished") { it.phase == RoomPhase.FINISHED }
        assertEquals(listOf(5, 5), finished.players.map { it.score })
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM live_match_player WHERE user_id = ? AND place = 1", Int::class.java, ada))
    }

    @Test
    fun `quick match puts strangers in one room and starts by itself`() {
        val ada = user("Ada")
        val bo = user("Bo")
        val first = rooms.quick(ada, Challenge.CATEGORY_SPRINT)
        val second = rooms.quick(bo, Challenge.CATEGORY_SPRINT)
        assertEquals(first.id, second.id)
        rooms.connected(first.id, ada)
        rooms.connected(first.id, bo)

        val round = awaitState(first.id, ada, "sprint round") { it.phase == RoomPhase.ROUND }
        assertTrue(round.category != null)
        rooms.handle(first.id, ada, ClientMessage("words", round = 0, words = listOf("dog", "happy", "dog")))
        rooms.handle(first.id, bo, ClientMessage("words", round = 0, words = listOf("nonsense")))
        val results = awaitState(first.id, bo, "results") { it.phase == RoomPhase.RESULTS || it.phase == RoomPhase.FINISHED }
        assertEquals(listOf("nonsense"), results.results!!.single { it.userId == bo }.answers.map { it.answer })
        assertEquals(2, results.results!!.single { it.userId == ada }.answers.size) // "dog" once
    }

    @Test
    fun `in complete the word reveals are personal and answers can't be changed`() {
        val ada = user("Ada")
        val bo = user("Bo")
        val room = rooms.create(ada, CreateRoomRequest(Challenge.COMPLETE_THE_WORD_EASY, RoomKind.PUBLIC))
        rooms.join(bo, room.id)
        rooms.connected(room.id, ada)
        rooms.connected(room.id, bo)
        rooms.handle(room.id, ada, ClientMessage("start"))
        val round = awaitState(room.id, ada, "word 1") { it.phase == RoomPhase.ROUND && it.round == 0 }

        rooms.handle(room.id, ada, ClientMessage("reveal", round = 0))
        val adaMask = rooms.state(room.id, ada)!!.hint!!.mask
        val boMask = rooms.state(room.id, bo)!!.hint!!.mask
        assertEquals(round.hint!!.mask.count { it == '_' } - 1, adaMask.count { it == '_' })
        assertEquals(round.hint!!.mask, boMask)

        rooms.handle(room.id, ada, ClientMessage("answer", round = 0, answer = "first"))
        rooms.handle(room.id, ada, ClientMessage("answer", round = 0, answer = "second")) // ignored
        rooms.handle(room.id, bo, ClientMessage("answer", round = 0, answer = "x"))
        val results = awaitState(room.id, ada, "word 1 results") { it.phase == RoomPhase.RESULTS }
        assertEquals("first", results.results!!.single { it.userId == ada }.answers.single().answer)
        assertTrue(results.correctAnswer!!.isNotEmpty())
    }

    @Test
    fun `rooms are listed and guarded`() {
        val ada = user("Ada")
        val bo = user("Bo")
        val eve = user("Eve")
        val group = groups.create(ada, "Club")
        groups.join(bo, group.inviteCode)

        val public = rooms.create(ada, CreateRoomRequest(Challenge.CATEGORY_SPRINT, RoomKind.PUBLIC))
        val forGroup = rooms.create(ada, CreateRoomRequest(Challenge.CATEGORY_SPRINT, RoomKind.FRIENDS, group.id))
        val private = rooms.create(ada, CreateRoomRequest(Challenge.CATEGORY_SPRINT, RoomKind.FRIENDS))

        val bosLobby = rooms.lobby(bo)
        assertTrue(public.id in bosLobby.open.map { it.id })
        assertEquals(listOf(forGroup.id), bosLobby.groups.map { it.id })
        assertTrue(private.id !in bosLobby.open.map { it.id })
        assertEquals(setOf(public.id, forGroup.id, private.id), rooms.lobby(ada).mine.map { it.id }.toSet())

        rooms.join(bo, forGroup.id) // a group member doesn't need the code
        assertEquals(HttpStatus.NOT_FOUND, statusOf { rooms.join(eve, forGroup.id) })
        assertEquals(HttpStatus.NOT_FOUND, statusOf { rooms.join(eve, private.id) })
        rooms.joinByCode(eve, private.code) // with the code anyone can

        // Ada and Eve are in, so it can start; nobody can join a match that has started
        rooms.handle(private.id, ada, ClientMessage("start"))
        assertEquals(HttpStatus.CONFLICT, statusOf { rooms.joinByCode(bo, private.code) })
    }
}
