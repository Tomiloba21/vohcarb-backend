package app.vohcarb.game.live

import app.vohcarb.game.AnswerJudge
import app.vohcarb.game.Challenge
import app.vohcarb.game.CompleteRules
import app.vohcarb.game.Content
import app.vohcarb.game.DailyContent
import app.vohcarb.game.Game
import app.vohcarb.game.HintDto
import app.vohcarb.game.Judged
import app.vohcarb.game.NpatEntry
import app.vohcarb.game.NpatRoundAnswers
import app.vohcarb.game.NpatRules
import app.vohcarb.game.Verdict
import app.vohcarb.social.GroupService
import app.vohcarb.user.AppUserRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LivePlayer(val userId: Long, val name: String) {
    var score = 0

    /** Open sockets (a player may have the app open twice). */
    var sockets = 0
    var left = false
    val connected get() = sockets > 0
}

/** One checked answer in the current round. */
class RoundAnswer(val key: String, val userId: Long, val category: NpatRules.Category?, val judged: Judged) {
    var stands = judged.verdict == Verdict.VALID
    var points = judged.points
}

class Room(
    val id: String,
    val code: String,
    val challenge: Challenge,
    val kind: RoomKind,
    val groupId: Long?,
    var hostId: Long,
) {
    val players = LinkedHashMap<Long, LivePlayer>()
    var phase = RoomPhase.LOBBY
    var content: Content? = null
    var round = -1
    var endsAt: Instant? = null
    var roundStartedAt: Instant = Instant.EPOCH

    /** Bumped on every phase change, so a timer set for an earlier phase does nothing. */
    var token = 0
    var timer: ScheduledFuture<*>? = null

    /** What each player handed in this round: NpatRoundAnswers, a word list, or a CompleteSubmission. */
    val submissions = HashMap<Long, Any>()
    val reveals = HashMap<Long, Int>()
    var answers: List<RoundAnswer> = emptyList()

    /** Answer key → voter → accepted. */
    val votes = HashMap<String, MutableMap<Long, Boolean>>()
    var lastActivity: Instant = Instant.now()

    val active get() = players.values.filter { !it.left }

    val rounds: Int
        get() = when (val c = content) {
            is Content.Npat -> c.letters.size
            is Content.Complete -> c.words.size
            is Content.Sprint -> 1
            null -> 0
        }
}

data class CompleteSubmission(val answer: String, val elapsedMs: Int)

/** Sends room states to players' sockets; the WebSocket handler implements it. */
fun interface RoomSender {
    fun send(roomId: String, userId: Long, message: Any)
}

/**
 * Live rooms, in memory on this one server (a restart ends running matches; finished ones are saved).
 * Every change to a room happens under that room's lock; afterwards each connected player gets a fresh state.
 * Timers move rooms on: countdown → round → (vote) → results → next round … → finished.
 */
@Service
class LiveRooms(
    private val content: DailyContent,
    private val judge: AnswerJudge,
    private val groups: GroupService,
    private val users: AppUserRepository,
    private val jdbc: JdbcTemplate,
    // Timings in milliseconds (short ones in tests)
    @Value("\${vohcarb.live.countdown-millis:3000}") private val countdownMillis: Long,
    @Value("\${vohcarb.live.round-millis:60000}") private val roundMillis: Long,
    @Value("\${vohcarb.live.word-millis:20000}") private val wordMillis: Long,
    @Value("\${vohcarb.live.vote-millis:15000}") private val voteMillis: Long,
    @Value("\${vohcarb.live.results-millis:5000}") private val resultsMillis: Long,
    @Value("\${vohcarb.live.quick-wait-millis:15000}") private val quickWaitMillis: Long,
    /** Time after a round's end for the last answers to arrive over the network. */
    @Value("\${vohcarb.live.grace-millis:2000}") private val graceMillis: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rooms = ConcurrentHashMap<String, Room>()
    private val timers = Executors.newScheduledThreadPool(2) { r -> Thread(r, "live-rooms").apply { isDaemon = true } }
    private val random = SecureRandom()

    @Volatile
    var sender: RoomSender = RoomSender { _, _, _ -> }

    init {
        timers.scheduleAtFixedRate({ runCatching { cleanUp() }.onFailure { log.warn("Room clean-up failed", it) } }, 1, 1, TimeUnit.MINUTES)
    }

    @PreDestroy
    fun shutdown() = timers.shutdownNow().let { }

    // Lobby (REST)

    fun lobby(userId: Long): LobbyDto {
        val myGroups = groups.list(userId).map { it.id }.toSet()
        val all = rooms.values.toList()
        fun summaries(filter: (Room) -> Boolean) = all.filter { r -> synchronized(r) { filter(r) } }.map(::summary)
        return LobbyDto(
            open = summaries { it.kind == RoomKind.PUBLIC && it.phase == RoomPhase.LOBBY && it.active.size < MAX_PLAYERS },
            groups = summaries { it.groupId in myGroups && it.phase == RoomPhase.LOBBY && userId !in it.players },
            mine = summaries { r -> r.players[userId]?.left == false && r.phase != RoomPhase.FINISHED },
        )
    }

    fun create(userId: Long, request: CreateRoomRequest): RoomSummaryDto {
        if (request.kind == RoomKind.QUICK) badRequest("Use quick match to play strangers")
        request.groupId?.let { groups.requireMember(userId, it) }
        val room = Room(UUID.randomUUID().toString(), newCode(), request.challenge, request.kind, request.groupId, userId)
        synchronized(room) { addPlayer(room, userId) }
        rooms[room.id] = room
        return summary(room)
    }

    fun joinByCode(userId: Long, code: String): RoomSummaryDto {
        val wanted = code.uppercase().filter { it.isLetterOrDigit() }
        val room = rooms.values.firstOrNull { it.code == wanted } ?: notFound("No room has that code")
        return join(userId, room, viaCode = true)
    }

    fun join(userId: Long, roomId: String): RoomSummaryDto = join(userId, room(roomId), viaCode = false)

    /** Joins a quick-match room for [challenge] that's still waiting, or opens one. */
    fun quick(userId: Long, challenge: Challenge): RoomSummaryDto {
        val waiting = rooms.values.firstOrNull { r ->
            synchronized(r) { r.kind == RoomKind.QUICK && r.challenge == challenge && r.phase == RoomPhase.LOBBY && r.active.size < MAX_PLAYERS }
        }
        if (waiting != null) return join(userId, waiting, viaCode = false)
        val room = Room(UUID.randomUUID().toString(), newCode(), challenge, RoomKind.QUICK, null, userId)
        synchronized(room) { addPlayer(room, userId) }
        rooms[room.id] = room
        return summary(room)
    }

    fun leave(userId: Long, roomId: String) {
        val room = rooms[roomId] ?: return
        change(room) {
            val player = room.players[userId] ?: return@change
            if (room.phase == RoomPhase.LOBBY) room.players.remove(userId) else player.left = true
            if (room.hostId == userId) room.active.firstOrNull()?.let { room.hostId = it.userId }
            when {
                room.active.isEmpty() -> close(room)
                room.kind == RoomKind.QUICK && room.phase == RoomPhase.LOBBY && room.active.size < MIN_PLAYERS -> {
                    room.token++ // stop the automatic start
                    room.timer?.cancel(false)
                    room.endsAt = null
                }
                room.phase == RoomPhase.ROUND -> endRoundIfAllIn(room)
                room.phase == RoomPhase.VOTING -> scoreIfAllVoted(room)
            }
        }
    }

    private fun join(userId: Long, room: Room, viaCode: Boolean): RoomSummaryDto {
        change(room) {
            val existing = room.players[userId]
            if (existing != null && !existing.left) return@change // already in: e.g. reopening the app
            when (room.kind) {
                RoomKind.FRIENDS -> if (!viaCode) {
                    val groupId = room.groupId ?: notFound("No room ${room.id}")
                    groups.requireMember(userId, groupId) // 404 to outsiders
                }
                RoomKind.PUBLIC, RoomKind.QUICK -> Unit
            }
            if (room.phase != RoomPhase.LOBBY) conflict("This match has already started")
            if (room.active.size >= MAX_PLAYERS) conflict("This room is full ($MAX_PLAYERS players)")
            addPlayer(room, userId)
            if (room.kind == RoomKind.QUICK) quickStartCheck(room)
        }
        return summary(room)
    }

    // Sockets

    /** A player's socket opened. False if they're not in that room (the socket is then closed). */
    fun connected(roomId: String, userId: Long): Boolean {
        val room = rooms[roomId] ?: return false
        var ok = false
        change(room) {
            val player = room.players[userId]
            if (player != null && !player.left) {
                player.sockets++
                ok = true
            }
        }
        return ok
    }

    fun disconnected(roomId: String, userId: Long) {
        val room = rooms[roomId] ?: return
        change(room) {
            val player = room.players[userId] ?: return@change
            player.sockets = (player.sockets - 1).coerceAtLeast(0)
            // Nobody waits for someone who dropped out
            if (room.phase == RoomPhase.ROUND) endRoundIfAllIn(room)
            if (room.phase == RoomPhase.VOTING) scoreIfAllVoted(room)
        }
    }

    fun handle(roomId: String, userId: Long, message: ClientMessage) {
        val room = room(roomId)
        change(room) {
            val player = room.players[userId]
            if (player == null || player.left) notFound("You're not in this room")
            when (message.type) {
                "start" -> start(room, userId)
                "npat" -> submit(room, userId, message.round, Game.NAME_PLACE_ANIMAL_THING) { message.answers ?: NpatRoundAnswers() }
                "words" -> submit(room, userId, message.round, Game.CATEGORY_SPRINT) { message.words.orEmpty().take(MAX_WORDS) }
                "answer" -> submit(room, userId, message.round, Game.COMPLETE_THE_WORD) {
                    CompleteSubmission(message.answer.orEmpty(), Duration.between(room.roundStartedAt, Instant.now()).toMillis().toInt())
                }
                "reveal" -> reveal(room, userId, message.round)
                "vote" -> vote(room, userId, message.key ?: badRequest("Which answer?"), message.accepted ?: badRequest("Accept or reject?"))
                else -> badRequest("Unknown message type '${message.type}'")
            }
        }
    }

    /** The room as [userId] sees it. */
    fun state(roomId: String, userId: Long): RoomStateDto? = rooms[roomId]?.let { synchronized(it) { state(it, userId) } }

    // Phases (all called under the room's lock)

    private fun start(room: Room, userId: Long) {
        if (room.phase != RoomPhase.LOBBY) conflict("Already started")
        if (room.kind != RoomKind.QUICK && userId != room.hostId) forbidden("Only the host can start")
        if (room.active.size < MIN_PLAYERS) conflict("At least $MIN_PLAYERS players are needed")
        startNow(room)
    }

    private fun startNow(room: Room) {
        room.content = content.random(room.challenge)
        room.players.values.forEach { it.score = 0 }
        moveTo(room, RoomPhase.COUNTDOWN, countdownMillis) { beginRound(room, 0) }
    }

    private fun beginRound(room: Room, round: Int) {
        room.round = round
        room.submissions.clear()
        room.reveals.clear()
        room.answers = emptyList()
        room.votes.clear()
        room.roundStartedAt = Instant.now()
        val length = if (room.challenge.game == Game.COMPLETE_THE_WORD) wordMillis else roundMillis
        moveTo(room, RoomPhase.ROUND, length, extraMillis = graceMillis) { endRound(room) }
    }

    private fun submit(room: Room, userId: Long, round: Int?, game: Game, value: () -> Any) {
        if (room.challenge.game != game) badRequest("Wrong answer type for this game")
        if (room.phase != RoomPhase.ROUND || round != room.round) return // late, or for an old round: ignore
        if (userId in room.submissions) return // answers are final
        room.submissions[userId] = value()
        endRoundIfAllIn(room)
    }

    private fun reveal(room: Room, userId: Long, round: Int?) {
        if (room.phase != RoomPhase.ROUND || round != room.round || room.challenge.game != Game.COMPLETE_THE_WORD) return
        if (userId in room.submissions) return
        room.reveals.merge(userId, 1, Int::plus)
    }

    private fun endRoundIfAllIn(room: Room) {
        val waitingFor = room.active.filter { it.connected && it.userId !in room.submissions }
        if (waitingFor.isEmpty()) endRound(room)
    }

    private fun endRound(room: Room) {
        if (room.phase != RoomPhase.ROUND) return
        room.answers = judgeRound(room)
        val toVote = room.answers.any { it.judged.verdict == Verdict.UNLISTED }
        if (toVote) moveTo(room, RoomPhase.VOTING, voteMillis) { scoreRound(room) } else scoreRound(room)
    }

    private fun vote(room: Room, userId: Long, key: String, accepted: Boolean) {
        if (room.phase != RoomPhase.VOTING) return
        val answer = room.answers.firstOrNull { it.key == key } ?: badRequest("No such answer")
        if (answer.judged.verdict != Verdict.UNLISTED) badRequest("Only names and places not in our lists are voted on")
        if (answer.userId == userId) badRequest("You can't vote on your own answer")
        room.votes.getOrPut(key) { HashMap() }[userId] = accepted
        scoreIfAllVoted(room)
    }

    private fun scoreIfAllVoted(room: Room) {
        val voters = room.active.filter { it.connected }.map { it.userId }
        val done = room.answers.filter { it.judged.verdict == Verdict.UNLISTED }
            .all { a -> voters.filter { it != a.userId }.all { it in room.votes[a.key].orEmpty() } }
        if (done) scoreRound(room)
    }

    private fun scoreRound(room: Room) {
        if (room.phase != RoomPhase.ROUND && room.phase != RoomPhase.VOTING) return
        if (room.challenge.game == Game.NAME_PLACE_ANIMAL_THING) {
            room.answers.forEach { a ->
                if (a.judged.verdict == Verdict.UNLISTED) {
                    val v = room.votes[a.key].orEmpty().values
                    a.stands = NpatRules.unlistedStands(v.count { it }, v.count { !it }, votingPossible = true)
                }
            }
            room.answers.groupBy { it.category }.values.forEach { group ->
                val points = NpatRules.score(group.associate { it.key to (if (it.stands) it.judged.normalized else null) })
                group.forEach { it.points = points.getValue(it.key) }
            }
        }
        room.answers.forEach { a -> room.players[a.userId]?.let { it.score += a.points } }
        val last = room.round + 1 >= room.rounds
        // Complete the word has ten short rounds: shorter pauses between them
        val pause = if (room.challenge.game == Game.COMPLETE_THE_WORD) resultsMillis * 3 / 5 else resultsMillis
        moveTo(room, RoomPhase.RESULTS, pause) { if (last) finish(room) else beginRound(room, room.round + 1) }
    }

    private fun finish(room: Room) {
        room.token++
        room.timer?.cancel(false)
        room.phase = RoomPhase.FINISHED
        room.endsAt = null
        room.lastActivity = Instant.now()
        runCatching { save(room) }.onFailure { log.warn("Couldn't save live match {}", room.id, it) }
    }

    private fun judgeRound(room: Room): List<RoundAnswer> = when (val c = room.content) {
        is Content.Npat -> {
            val letter = c.letters[room.round]
            val entries = room.submissions.flatMap { (userId, value) ->
                val a = value as NpatRoundAnswers
                listOf(
                    NpatRules.Category.NAME to a.name, NpatRules.Category.PLACE to a.place,
                    NpatRules.Category.ANIMAL to a.animal, NpatRules.Category.THING to a.thing,
                ).mapNotNull { (category, text) -> text?.let { NpatEntry("$userId:${category.name}" to (userId to category), letter, category, it) } }
            }
            judge.npat(entries).map { (key, j) -> RoundAnswer(key.first, key.second.first, key.second.second, j) }
        }
        is Content.Sprint -> room.submissions.flatMap { (userId, value) ->
            @Suppress("UNCHECKED_CAST")
            judge.sprint(c.category.slug, value as List<String>).mapIndexed { i, j -> RoundAnswer("$userId:$i", userId, null, j) }
        }
        is Content.Complete -> room.submissions.mapNotNull { (userId, value) ->
            val s = value as CompleteSubmission
            if (s.answer.isBlank()) return@mapNotNull null
            RoundAnswer("$userId", userId, null, judge.complete(c.words[room.round], c.hint, s.answer, s.elapsedMs, room.reveals[userId] ?: 0))
        }
        null -> emptyList()
    }

    /** Sets the phase and a timer for [then]; a later phase change makes the timer do nothing. */
    private fun moveTo(room: Room, phase: RoomPhase, millis: Long, extraMillis: Long = 0, then: () -> Unit) {
        room.phase = phase
        room.token++
        val token = room.token
        room.endsAt = Instant.now().plusMillis(millis)
        room.timer?.cancel(false)
        room.timer = timers.schedule({ timer(room, token, then) }, millis + extraMillis, TimeUnit.MILLISECONDS)
    }

    private fun timer(room: Room, token: Int, action: () -> Unit) {
        runCatching { change(room) { if (room.token == token) action() } }
            .onFailure { log.warn("Live room {} timer failed", room.id, it) }
    }

    /** A quick-match room starts by itself [quickWaitMillis] after its second player arrives, or at once when full. */
    private fun quickStartCheck(room: Room) {
        when {
            room.active.size >= MAX_PLAYERS -> startNow(room)
            room.active.size >= MIN_PLAYERS && room.endsAt == null -> {
                room.token++
                val token = room.token
                room.endsAt = Instant.now().plusMillis(quickWaitMillis)
                room.timer = timers.schedule(
                    { timer(room, token) { if (room.phase == RoomPhase.LOBBY && room.active.size >= MIN_PLAYERS) startNow(room) } },
                    quickWaitMillis, TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    // Helpers

    /** Runs [block] under the room's lock, then sends everyone the new state (also when [block] throws). */
    private fun change(room: Room, block: () -> Unit) {
        try {
            synchronized(room) {
                room.lastActivity = Instant.now()
                block()
            }
        } finally {
            broadcast(room)
        }
    }

    private fun broadcast(room: Room) {
        val states = synchronized(room) { room.players.values.filter { it.connected }.map { it.userId to state(room, it.userId) } }
        states.forEach { (userId, state) -> sender.send(room.id, userId, StateMessageDto(state = state)) }
    }

    private fun state(room: Room, viewer: Long): RoomStateDto {
        val c = room.content
        val inRound = room.phase == RoomPhase.ROUND || room.phase == RoomPhase.VOTING || room.phase == RoomPhase.RESULTS
        val complete = (c as? Content.Complete)?.takeIf { inRound }
        val word = complete?.words?.getOrNull(room.round)
        val hint = word?.let {
            val mask = (1..(room.reveals[viewer] ?: 0)).fold(it.mask) { m, _ -> CompleteRules.reveal(m, it.word) }
            HintDto(room.round, it.definition, it.partOfSpeech, it.word.length, mask)
        }
        val names = room.players.mapValues { it.value.name }
        val votes = if (room.phase == RoomPhase.VOTING) {
            room.answers.filter { it.judged.verdict == Verdict.UNLISTED }.map { a ->
                val v = room.votes[a.key].orEmpty()
                LiveVoteDto(a.key, names[a.userId].orEmpty(), a.category?.name.orEmpty(), a.judged.text, v.values.count { it }, v.values.count { !it }, v[viewer], a.userId == viewer)
            }
        } else {
            null
        }
        val results = if (room.phase == RoomPhase.RESULTS || (room.phase == RoomPhase.FINISHED && room.answers.isNotEmpty())) {
            room.active.map { p ->
                val mine = room.answers.filter { it.userId == p.userId }
                LiveRoundResultDto(
                    p.userId, p.name, mine.sumOf { it.points },
                    mine.map { LiveAnswerDto(it.category?.name, it.judged.text, it.judged.verdict, it.stands, it.points) },
                )
            }
        } else {
            null
        }
        return RoomStateDto(
            roomId = room.id,
            code = room.code,
            challenge = room.challenge,
            title = room.challenge.title,
            kind = room.kind,
            groupId = room.groupId,
            hostId = room.hostId,
            you = viewer,
            phase = room.phase,
            round = room.round,
            rounds = room.rounds,
            endsAt = room.endsAt,
            serverTime = Instant.now(),
            minPlayers = MIN_PLAYERS,
            maxPlayers = MAX_PLAYERS,
            players = room.active.sortedByDescending { it.score }.map {
                LivePlayerDto(it.userId, it.name, it.score, it.connected, it.userId in room.submissions)
            },
            letter = (c as? Content.Npat)?.takeIf { inRound }?.letters?.getOrNull(room.round)?.uppercase(),
            category = (c as? Content.Sprint)?.takeIf { inRound }?.category,
            hint = hint,
            youSubmitted = viewer in room.submissions,
            votes = votes,
            results = results,
            correctAnswer = word?.word?.takeIf { room.phase == RoomPhase.RESULTS || room.phase == RoomPhase.FINISHED },
        )
    }

    private fun summary(room: Room): RoomSummaryDto = synchronized(room) {
        RoomSummaryDto(
            room.id, room.code, room.challenge, room.challenge.title, room.kind, room.groupId,
            room.players[room.hostId]?.name.orEmpty(), room.active.size, MAX_PLAYERS, room.phase,
        )
    }

    private fun addPlayer(room: Room, userId: Long) {
        val name = users.findById(userId).map { it.displayName }.orElse("Player")
        room.players[userId] = LivePlayer(userId, name)
    }

    private fun close(room: Room) {
        room.token++
        room.timer?.cancel(false)
        rooms.remove(room.id)
    }

    /** Finished rooms stay 10 minutes for the results; rooms nobody is connected to go after 10 minutes. */
    private fun cleanUp() {
        val cutoff = Instant.now().minus(Duration.ofMinutes(10))
        rooms.values.forEach { room ->
            synchronized(room) {
                val idle = room.players.values.none { it.connected } && room.lastActivity.isBefore(cutoff)
                if ((room.phase == RoomPhase.FINISHED || idle) && room.lastActivity.isBefore(cutoff)) close(room)
            }
        }
    }

    private fun save(room: Room) {
        val keys = GeneratedKeyHolder()
        jdbc.update({ con ->
            con.prepareStatement(
                "INSERT INTO live_match (challenge, room_kind, group_id, finished_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setString(1, room.challenge.name)
                setString(2, room.kind.name)
                setObject(3, room.groupId)
                setTimestamp(4, Timestamp.from(Instant.now()))
            }
        }, keys)
        val matchId = keys.keys?.values?.firstOrNull()?.let { (it as Number).toLong() } ?: keys.key!!.toLong()
        var place = 0
        var previous: Int? = null
        room.active.sortedByDescending { it.score }.forEachIndexed { i, p ->
            if (p.score != previous) place = i + 1
            previous = p.score
            jdbc.update("INSERT INTO live_match_player (match_id, user_id, score, place) VALUES (?, ?, ?, ?)", matchId, p.userId, p.score, place)
        }
    }

    private fun room(id: String) = rooms[id] ?: notFound("No room $id")

    /** Six characters without look-alikes (no 0/O, 1/I/L). */
    private fun newCode(): String {
        while (true) {
            val code = (1..6).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
            if (rooms.values.none { it.code == code }) return code
        }
    }

    private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)
    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
    private fun forbidden(message: String): Nothing = throw ResponseStatusException(HttpStatus.FORBIDDEN, message)
    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 6
        const val MAX_WORDS = 200
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }
}
