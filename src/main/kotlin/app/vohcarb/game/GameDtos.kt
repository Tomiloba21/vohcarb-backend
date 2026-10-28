package app.vohcarb.game

import app.vohcarb.catalog.CategoryRef
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

data class DailyChallengeDto(
    val challenge: Challenge,
    val game: Game,
    val hint: HintLevel?,
    val title: String,
    val description: String,
    val timeLimitSeconds: Int,
    /** NOT_STARTED, IN_PROGRESS or DONE. */
    val status: PlayStatus,
    /** Once done; Name-Place-Animal-Thing scores can still change as others play and vote. */
    val score: Int?,
    /** Your play, once started: for its result. */
    val playId: Long?,
)

enum class PlayStatus { NOT_STARTED, IN_PROGRESS, DONE }

data class DailyOverviewDto(val date: LocalDate, val groupId: Long?, val challenges: List<DailyChallengeDto>)

/** What the player sees: everything but the answers. */
data class PlayDto(
    val playId: Long,
    val challenge: Challenge,
    val date: LocalDate,
    val startedAt: Instant,
    /** Answers must be in by then (a few seconds' grace is allowed for the network). */
    val deadline: Instant,
    val letters: List<String>? = null,
    val category: CategoryRef? = null,
    val words: List<HintDto>? = null,
)

data class HintDto(val index: Int, val definition: String, val partOfSpeech: String, val length: Int, val mask: String)

data class RevealRequest(@field:Min(0) val index: Int)

data class NpatRoundAnswers(
    @field:Size(max = 64) val name: String? = null,
    @field:Size(max = 64) val place: String? = null,
    @field:Size(max = 64) val animal: String? = null,
    @field:Size(max = 64) val thing: String? = null,
)

data class CompleteAnswer(
    @field:Min(0) val index: Int,
    @field:Size(max = 64) val answer: String,
    /** How long the player took on this word, from the device. */
    @field:Min(0) @field:Max(600_000) val elapsedMs: Int? = null,
)

/** Fill the part for the game being played. */
data class SubmitRequest(
    @field:Size(max = NpatRules.ROUNDS) @field:Valid val rounds: List<NpatRoundAnswers>? = null,
    /** Words longer than 64 characters are cut. */
    @field:Size(max = SprintRules.MAX_WORDS) val words: List<String>? = null,
    @field:Size(max = CompleteRules.WORDS) @field:Valid val answers: List<CompleteAnswer>? = null,
)

data class AnswerResultDto(
    val id: Long,
    val item: Int,
    /** NAME, PLACE, ANIMAL, THING, or null. */
    val category: String?,
    val answer: String,
    val verdict: Verdict,
    /** For UNLISTED answers: whether it currently stands after votes. */
    val stands: Boolean,
    val points: Int,
    /** Complete-the-word: the word it was. */
    val correctAnswer: String? = null,
)

data class ResultDto(
    val playId: Long,
    val challenge: Challenge,
    val date: LocalDate,
    val score: Int,
    /** Answers came in after the deadline and didn't count. */
    val late: Boolean,
    val answers: List<AnswerResultDto>,
)

data class GameLeaderboardRow(val rank: Int, val userId: Long, val displayName: String, val score: Int, val isMe: Boolean)

data class GameLeaderboardDto(
    val challenge: Challenge,
    val date: LocalDate,
    val groupId: Long?,
    val players: Int,
    /** The top players, plus you if you're further down. */
    val rows: List<GameLeaderboardRow>,
)

data class ReviewItemDto(
    val answerId: Long,
    val playerName: String,
    val letter: String,
    val category: String,
    val answer: String,
    val accepts: Int,
    val rejects: Int,
    /** Your vote, if any. */
    val myVote: Boolean?,
)

data class VoteRequest(val accepted: Boolean)
