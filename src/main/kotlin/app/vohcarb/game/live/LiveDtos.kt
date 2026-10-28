package app.vohcarb.game.live

import app.vohcarb.catalog.CategoryRef
import app.vohcarb.game.Challenge
import app.vohcarb.game.HintDto
import app.vohcarb.game.NpatRoundAnswers
import app.vohcarb.game.Verdict
import java.time.Instant

// Live play (docs/games.md, phase G3). Rooms are created and joined over REST; the match itself runs over a
// WebSocket per room (/ws/games/rooms/{id}) where the server sends a RoomStateDto after every change.

enum class RoomKind {
    /** Private: join with the code, or from the group it was made for. */
    FRIENDS,

    /** Listed in the open lobby. */
    PUBLIC,

    /** Made by quick match; starts by itself once enough players are in. */
    QUICK,
}

enum class RoomPhase { LOBBY, COUNTDOWN, ROUND, VOTING, RESULTS, FINISHED }

data class CreateRoomRequest(val challenge: Challenge, val kind: RoomKind = RoomKind.FRIENDS, val groupId: Long? = null)

data class JoinByCodeRequest(val code: String)

data class QuickMatchRequest(val challenge: Challenge)

data class RoomSummaryDto(
    val id: String,
    val code: String,
    val challenge: Challenge,
    val title: String,
    val kind: RoomKind,
    val groupId: Long?,
    val hostName: String,
    val players: Int,
    val maxPlayers: Int,
    val phase: RoomPhase,
)

data class LobbyDto(
    /** Public rooms waiting for players. */
    val open: List<RoomSummaryDto>,
    /** Rooms made for your groups, waiting for players. */
    val groups: List<RoomSummaryDto>,
    /** Rooms you're in that haven't finished. */
    val mine: List<RoomSummaryDto>,
)

data class LivePlayerDto(
    val userId: Long,
    val name: String,
    val score: Int,
    val connected: Boolean,
    /** Handed in this round. */
    val submitted: Boolean,
)

/** An unlisted name or place to accept or reject (Name-Place-Animal-Thing). */
data class LiveVoteDto(
    val key: String,
    val playerName: String,
    val category: String,
    val answer: String,
    val accepts: Int,
    val rejects: Int,
    val myVote: Boolean?,
    /** Your own answer: you can't vote on it. */
    val mine: Boolean,
)

data class LiveAnswerDto(val category: String?, val answer: String, val verdict: Verdict, val stands: Boolean, val points: Int)

data class LiveRoundResultDto(val userId: Long, val name: String, val roundPoints: Int, val answers: List<LiveAnswerDto>)

data class RoomStateDto(
    val roomId: String,
    val code: String,
    val challenge: Challenge,
    val title: String,
    val kind: RoomKind,
    val groupId: Long?,
    val hostId: Long,
    val you: Long,
    val phase: RoomPhase,
    /** 0-based; -1 before the first round. */
    val round: Int,
    val rounds: Int,
    /** When the current phase ends; for a quick-match lobby, when it starts by itself. */
    val endsAt: Instant?,
    /** The server's clock, so phones can correct for theirs. */
    val serverTime: Instant,
    val minPlayers: Int,
    val maxPlayers: Int,
    val players: List<LivePlayerDto>,
    // This round's prompt
    val letter: String? = null,
    val category: CategoryRef? = null,
    /** Complete the word: the hint, with your own revealed letters. */
    val hint: HintDto? = null,
    val youSubmitted: Boolean = false,
    // After a round
    val votes: List<LiveVoteDto>? = null,
    val results: List<LiveRoundResultDto>? = null,
    /** Complete the word: the word that was asked. */
    val correctAnswer: String? = null,
)

/** What a phone sends over the socket; `type` says which fields are used. */
data class ClientMessage(
    /** start, npat, words, answer, reveal or vote. */
    val type: String,
    val round: Int? = null,
    val answers: NpatRoundAnswers? = null,
    val words: List<String>? = null,
    val answer: String? = null,
    val key: String? = null,
    val accepted: Boolean? = null,
)

/** Sent instead of a state when an action can't be done. */
data class ErrorMessageDto(val type: String = "error", val message: String)

data class StateMessageDto(val type: String = "state", val state: RoomStateDto)
