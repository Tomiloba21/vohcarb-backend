package app.vohcarb.game.live

import app.vohcarb.auth.TokenService
import app.vohcarb.game.Challenge
import app.vohcarb.user.UserService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The live socket end to end: a real server, a real WebSocket client, the bearer token in the handshake. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveSocketTest(
    @Autowired private val rooms: LiveRooms,
    @Autowired private val users: UserService,
    @Autowired private val tokens: TokenService,
    @Autowired private val json: ObjectMapper,
    @LocalServerPort private val port: Int,
) {

    private class Client : TextWebSocketHandler() {
        val messages = LinkedBlockingQueue<String>()
        val closed = CompletableFuture<CloseStatus>()
        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            messages += message.payload
        }
        override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
            closed.complete(status)
        }
    }

    private fun user(name: String): Pair<Long, String> {
        val email = "$name-${UUID.randomUUID()}@example.com"
        val user = users.signIn("dev", email, email, name)
        return user.id!! to tokens.issue(user).accessToken
    }

    private fun connect(roomId: String, token: String): Pair<WebSocketSession, Client> {
        val client = Client()
        val headers = WebSocketHttpHeaders().apply { setBearerAuth(token) }
        val session = StandardWebSocketClient()
            .execute(client, headers, URI("ws://localhost:$port/ws/games/rooms/$roomId"))
            .get(5, TimeUnit.SECONDS)
        return session to client
    }

    private fun Client.nextState(test: (RoomStateDto) -> Boolean = { true }): RoomStateDto {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val text = messages.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val tree = json.readTree(text)
            if (tree["type"].asString() != "state") continue
            val state = json.treeToValue(tree["state"], RoomStateDto::class.java)
            if (test(state)) return state
        }
        error("No matching state")
    }

    @Test
    fun `players get room states over the socket and act through it`() {
        val (ada, adaToken) = user("Ada")
        val (bo, boToken) = user("Bo")
        val room = rooms.create(ada, CreateRoomRequest(Challenge.CATEGORY_SPRINT, RoomKind.FRIENDS))
        rooms.joinByCode(bo, room.code)

        val (adaSocket, adaClient) = connect(room.id, adaToken)
        val (_, boClient) = connect(room.id, boToken)
        val lobby = adaClient.nextState { it.players.size == 2 && it.players.all { p -> p.connected } }
        assertEquals(ada, lobby.you)
        assertEquals(RoomPhase.LOBBY, lobby.phase)

        adaSocket.sendMessage(TextMessage("""{"type":"start"}"""))
        val round = boClient.nextState { it.phase == RoomPhase.ROUND }
        assertEquals(bo, round.you)
        assertNotNull(round.category)

        // Bo can't start or vote now; errors come back as messages, not closed sockets
        adaSocket.sendMessage(TextMessage("""{"type":"start"}"""))
        val error = generateSequence { adaClient.messages.poll(2, TimeUnit.SECONDS) }.map { json.readTree(it) }.first { it["type"].asString() == "error" }
        assertEquals("Already started", error["message"].asString())
    }

    @Test
    fun `a socket for a room you haven't joined is closed`() {
        val (ada, _) = user("Ada")
        val (_, eveToken) = user("Eve")
        val room = rooms.create(ada, CreateRoomRequest(Challenge.CATEGORY_SPRINT, RoomKind.FRIENDS))
        val (_, eve) = connect(room.id, eveToken)
        assertEquals(4404, eve.closed.get(5, TimeUnit.SECONDS).code)
    }
}
