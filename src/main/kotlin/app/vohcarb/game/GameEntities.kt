package app.vohcarb.game

import app.vohcarb.user.now
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "game_play")
class GamePlay(
    val userId: Long,
    @Enumerated(EnumType.STRING) val challenge: Challenge,
    val playDate: LocalDate,
    /** null for the global challenge. */
    val groupId: Long?,
    /** The group id, or 0 for the global challenge. */
    val scopeKey: Long,
    val startedAt: Instant = now(),
    var submittedAt: Instant? = null,
    /** Complete-the-word: letters revealed per word, comma-separated. */
    var reveals: String = "",
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
) {
    fun revealCounts(words: Int): MutableList<Int> =
        reveals.split(',').mapNotNull { it.toIntOrNull() }.toMutableList().also { while (it.size < words) it += 0 }
}

@Entity
@Table(name = "game_answer")
class GameAnswer(
    val playId: Long,
    val item: Int,
    /** NpatRules.Category name, or "" for the other games. */
    val slot: String,
    val answer: String,
    val normalized: String,
    @Enumerated(EnumType.STRING) val verdict: Verdict,
    val points: Int,
    val elapsedMs: Int? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
)

data class GameVoteId(val answerId: Long = 0, val voterId: Long = 0) : Serializable

@Entity
@Table(name = "game_vote")
@IdClass(GameVoteId::class)
class GameVote(
    @Id val answerId: Long,
    @Id val voterId: Long,
    var accepted: Boolean,
)

interface GamePlayRepository : JpaRepository<GamePlay, Long> {

    fun findByUserIdAndChallengeAndPlayDateAndScopeKey(userId: Long, challenge: Challenge, playDate: LocalDate, scopeKey: Long): GamePlay?

    fun findByUserIdAndPlayDateAndScopeKey(userId: Long, playDate: LocalDate, scopeKey: Long): List<GamePlay>

    @Query("SELECT p FROM GamePlay p WHERE p.challenge = :challenge AND p.playDate = :date AND p.scopeKey = :scope AND p.submittedAt IS NOT NULL")
    fun findSubmitted(@Param("challenge") challenge: Challenge, @Param("date") date: LocalDate, @Param("scope") scopeKey: Long): List<GamePlay>

    @Modifying
    @Query("DELETE FROM GamePlay p WHERE p.groupId = :groupId")
    fun deleteByGroupId(@Param("groupId") groupId: Long)
}

interface GameAnswerRepository : JpaRepository<GameAnswer, Long> {

    fun findByPlayIdIn(playIds: Collection<Long>): List<GameAnswer>

    @Modifying
    @Query("DELETE FROM GameAnswer a WHERE a.playId IN (SELECT p.id FROM GamePlay p WHERE p.groupId = :groupId)")
    fun deleteByGroupId(@Param("groupId") groupId: Long)
}

interface GameVoteRepository : JpaRepository<GameVote, GameVoteId> {

    fun findByAnswerIdIn(answerIds: Collection<Long>): List<GameVote>

    @Modifying
    @Query(
        "DELETE FROM GameVote v WHERE v.answerId IN " +
            "(SELECT a.id FROM GameAnswer a WHERE a.playId IN (SELECT p.id FROM GamePlay p WHERE p.groupId = :groupId))",
    )
    fun deleteByGroupId(@Param("groupId") groupId: Long)
}

/** Deletes a group's game data, before the group itself is deleted (see GroupService). */
@Service
class GroupGameData(
    private val plays: GamePlayRepository,
    private val answers: GameAnswerRepository,
    private val votes: GameVoteRepository,
) {
    fun deleteGroup(groupId: Long) {
        votes.deleteByGroupId(groupId)
        answers.deleteByGroupId(groupId)
        plays.deleteByGroupId(groupId)
    }
}
