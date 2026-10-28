package app.vohcarb.game.live

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * `wss://…/ws/games/rooms/{roomId}`: one socket per player per room. The handshake is a normal HTTP request, so
 * the usual `Authorization: Bearer` header signs it in; you must have joined the room first (over REST).
 */
@Component
class LiveSocketHandler(private val rooms: LiveRooms, private val json: ObjectMapper) : TextWebSocketHandler(), RoomSender {

    private val log = LoggerFactory.getLogger(javaClass)

    /** roomId → userId → that player's sockets (thread-safe wrappers). */
    private val sockets = ConcurrentHashMap<String, ConcurrentHashMap<Long, MutableSet<WebSocketSession>>>()

    init {
        rooms.sender = this
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val userId = userId(session) ?: return session.close(CloseStatus.POLICY_VIOLATION.withReason("Sign in first"))
        val roomId = roomId(session)
        val safe = ConcurrentWebSocketSessionDecorator(session, SEND_TIMEOUT_MS, BUFFER_BYTES)
        session.attributes[SAFE] = safe
        sockets.computeIfAbsent(roomId) { ConcurrentHashMap() }.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(safe)
        if (!rooms.connected(roomId, userId)) {
            forget(roomId, userId, safe)
            session.close(CloseStatus(NOT_IN_ROOM, "Join the room first"))
        }
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val userId = userId(session) ?: return
        val roomId = roomId(session)
        try {
            rooms.handle(roomId, userId, json.readValue(message.payload, ClientMessage::class.java))
        } catch (e: ResponseStatusException) {
            sendTo(session, ErrorMessageDto(message = e.reason ?: "Can't do that"))
        } catch (e: Exception) {
            log.debug("Bad live message from {} in {}", userId, roomId, e)
            sendTo(session, ErrorMessageDto(message = "Couldn't read that message"))
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val userId = userId(session) ?: return
        val roomId = roomId(session)
        val safe = session.attributes[SAFE] as? WebSocketSession ?: return
        if (forget(roomId, userId, safe)) rooms.disconnected(roomId, userId)
    }

    override fun send(roomId: String, userId: Long, message: Any) {
        val text = TextMessage(json.writeValueAsString(message))
        sockets[roomId]?.get(userId)?.forEach { s ->
            runCatching { if (s.isOpen) s.sendMessage(text) }.onFailure { log.debug("Couldn't send to {} in {}", userId, roomId, it) }
        }
    }

    private fun sendTo(session: WebSocketSession, message: Any) {
        val safe = session.attributes[SAFE] as? WebSocketSession ?: session
        runCatching { safe.sendMessage(TextMessage(json.writeValueAsString(message))) }
    }

    /** Removes the socket; true if it was registered. */
    private fun forget(roomId: String, userId: Long, safe: WebSocketSession): Boolean {
        val removed = sockets[roomId]?.get(userId)?.remove(safe) == true
        sockets[roomId]?.let { byUser ->
            if (byUser[userId]?.isEmpty() == true) byUser.remove(userId)
            if (byUser.isEmpty()) sockets.remove(roomId)
        }
        return removed
    }

    private fun userId(session: WebSocketSession): Long? =
        (session.principal as? JwtAuthenticationToken)?.token?.subject?.toLongOrNull()

    private fun roomId(session: WebSocketSession) = session.uri?.path.orEmpty().substringAfterLast('/')

    private companion object {
        const val SAFE = "vohcarb.safeSession"
        const val SEND_TIMEOUT_MS = 5_000
        const val BUFFER_BYTES = 256 * 1024
        const val NOT_IN_ROOM = 4404
    }
}

@Configuration
@EnableWebSocket
class LiveSocketConfig(private val handler: LiveSocketHandler) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        // Phones aren't browsers: no Origin check needed (the bearer token is what counts)
        registry.addHandler(handler, "/ws/games/rooms/*").setAllowedOrigins("*")
    }
}
