package app.vohcarb.social

import app.vohcarb.catalog.WordSummaryDto
import app.vohcarb.catalog.toSummary
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateGroupRequest(@field:NotBlank @field:Size(max = 64) val name: String)

data class JoinGroupRequest(@field:NotBlank @field:Size(max = 32) val code: String)

data class AddGroupWordRequest(
    @field:NotBlank val word: String,
    @field:Size(max = 280) val note: String? = null,
)

/** What members see of each other: no email. */
data class MemberDto(val userId: Long, val displayName: String, val role: GroupRole, val joinedAt: Instant)

data class GroupSummaryDto(val id: Long, val name: String, val myRole: GroupRole, val memberCount: Long)

data class GroupDetailDto(
    val id: Long,
    val name: String,
    /** Share this with friends so they can join. */
    val inviteCode: String,
    val myRole: GroupRole,
    val createdAt: Instant,
    val members: List<MemberDto>,
)

data class GroupWordDto(
    val word: WordSummaryDto,
    val addedBy: MemberRef,
    val note: String?,
    val addedAt: Instant,
)

data class MemberRef(val userId: Long, val displayName: String)

data class LeaderboardRow(
    val rank: Int,
    val userId: Long,
    val displayName: String,
    /** Answers in the period. */
    val reviews: Long,
    val streakDays: Int,
    /** Words with a review interval of three weeks or more. */
    val wordsMastered: Long,
    val isMe: Boolean,
)

data class LeaderboardDto(val days: Int, val since: Instant, val rows: List<LeaderboardRow>)

fun GroupMember.toDto() = MemberDto(user.id!!, user.displayName, role, joinedAt)

fun GroupWord.toDto() = GroupWordDto(word.toSummary(), MemberRef(addedBy.id!!, addedBy.displayName), note, addedAt)
