package app.vohcarb.social

import app.vohcarb.catalog.PageResponse
import app.vohcarb.catalog.WordOfTheDayDto
import app.vohcarb.catalog.WordOfTheDayService
import app.vohcarb.catalog.WordRepository
import app.vohcarb.game.GroupGameData
import app.vohcarb.user.AppUserRepository
import app.vohcarb.user.now
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Friend groups: anyone with a group's invite code can join; the owner can remove members and change the code.
 * Groups a user isn't in answer 404, so outsiders can't tell which groups exist.
 */
@Service
@Transactional
class GroupService(
    private val groups: FriendGroupRepository,
    private val members: GroupMemberRepository,
    private val groupWords: GroupWordRepository,
    private val users: AppUserRepository,
    private val words: WordRepository,
    private val wordOfTheDay: WordOfTheDayService,
    private val gameData: GroupGameData,
) {

    private val random = SecureRandom()

    fun create(userId: Long, name: String): GroupDetailDto {
        if (members.countGroups(userId) >= MAX_GROUPS_PER_USER) conflict("You can be in at most $MAX_GROUPS_PER_USER groups")
        val groupId = groups.save(FriendGroup(name = name.trim(), inviteCode = newInviteCode(), createdBy = userId)).id!!
        members.save(GroupMember(GroupMemberId(groupId, userId), users.getReferenceById(userId), GroupRole.OWNER))
        return detail(userId, groupId)
    }

    @Transactional(readOnly = true)
    fun list(userId: Long): List<GroupSummaryDto> = members.findByUser(userId).map { m ->
        val group = groups.getReferenceById(m.id.groupId)
        GroupSummaryDto(group.id!!, group.name, m.role, members.countMembers(m.id.groupId))
    }

    @Transactional(readOnly = true)
    fun detail(userId: Long, groupId: Long): GroupDetailDto {
        val me = requireMember(userId, groupId)
        val group = groups.getReferenceById(groupId)
        return GroupDetailDto(group.id!!, group.name, group.inviteCode, me.role, group.createdAt, members.findMembers(groupId).map { it.toDto() })
    }

    fun join(userId: Long, code: String): GroupDetailDto {
        val group = groups.findByInviteCode(normalize(code))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No group has that invite code")
        val groupId = group.id!!
        if (members.existsById(GroupMemberId(groupId, userId))) return detail(userId, groupId)
        if (members.countMembers(groupId) >= MAX_MEMBERS) conflict("This group is full ($MAX_MEMBERS members)")
        if (members.countGroups(userId) >= MAX_GROUPS_PER_USER) conflict("You can be in at most $MAX_GROUPS_PER_USER groups")
        members.save(GroupMember(GroupMemberId(groupId, userId), users.getReferenceById(userId), GroupRole.MEMBER))
        return detail(userId, groupId)
    }

    /** Leaving as owner hands the group to the longest-standing member; the last one out deletes it. */
    fun leave(userId: Long, groupId: Long) {
        val me = requireMember(userId, groupId)
        members.delete(me)
        members.flush()
        val remaining = members.findMembers(groupId)
        when {
            remaining.isEmpty() -> deleteGroup(groupId)
            me.role == GroupRole.OWNER -> remaining.first().role = GroupRole.OWNER
        }
    }

    fun removeMember(userId: Long, groupId: Long, memberId: Long) {
        requireOwner(userId, groupId)
        if (memberId == userId) badRequest("To leave your own group, use leave")
        val member = members.findById(GroupMemberId(groupId, memberId))
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No such member") }
        members.delete(member)
    }

    fun newInviteCode(userId: Long, groupId: Long): GroupDetailDto {
        requireOwner(userId, groupId)
        groups.getReferenceById(groupId).inviteCode = newInviteCode()
        return detail(userId, groupId)
    }

    fun delete(userId: Long, groupId: Long) {
        requireOwner(userId, groupId)
        deleteGroup(groupId)
    }

    @Transactional(readOnly = true)
    fun words(userId: Long, groupId: Long, page: Int, size: Int): PageResponse<GroupWordDto> {
        requireMember(userId, groupId)
        val result = groupWords.findByGroupId(groupId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "addedAt")))
        return PageResponse.of(result) { it.toDto() }
    }

    /** Adding a word that's already on the list returns the existing entry. */
    fun addWord(userId: Long, groupId: Long, request: AddGroupWordRequest): GroupWordDto {
        requireMember(userId, groupId)
        val word = words.findBySlug(request.word) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No word '${request.word}' found")
        groupWords.findByGroupIdAndWordId(groupId, word.id)?.let { return it.toDto() }
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        return groupWords.save(GroupWord(groupId, word, users.getReferenceById(userId), note)).toDto()
    }

    /** Whoever added a word can remove it, and so can the owner. */
    fun removeWord(userId: Long, groupId: Long, slug: String) {
        val me = requireMember(userId, groupId)
        val word = words.findBySlug(slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No word '$slug' found")
        val entry = groupWords.findByGroupIdAndWordId(groupId, word.id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "'$slug' isn't on this group's list")
        if (entry.addedBy.id != userId && me.role != GroupRole.OWNER) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only whoever added a word, or the owner, can remove it")
        }
        groupWords.delete(entry)
    }

    /** The group's word of the day (UTC date): the same for every member, different from other groups'. */
    @Transactional(readOnly = true)
    fun wordOfTheDay(userId: Long, groupId: Long): WordOfTheDayDto {
        requireMember(userId, groupId)
        return wordOfTheDay.forDate(LocalDate.ofInstant(now(), ZoneOffset.UTC), seed = groupId)
    }

    @Transactional(readOnly = true)
    fun requireMember(userId: Long, groupId: Long): GroupMember =
        members.findById(GroupMemberId(groupId, userId))
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No group $groupId found") }

    private fun requireOwner(userId: Long, groupId: Long): GroupMember {
        val me = requireMember(userId, groupId)
        if (me.role != GroupRole.OWNER) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the group owner can do that")
        return me
    }

    private fun deleteGroup(groupId: Long) {
        gameData.deleteGroup(groupId)
        groupWords.deleteByGroupId(groupId)
        members.deleteByGroup(groupId)
        groups.deleteById(groupId)
    }

    private fun newInviteCode(): String {
        repeat(10) {
            val code = (1..INVITE_CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
            if (!groups.existsByInviteCode(code)) return code
        }
        error("Could not generate a unique invite code")
    }

    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    companion object {
        const val MAX_MEMBERS = 50
        const val MAX_GROUPS_PER_USER = 20
        const val INVITE_CODE_LENGTH = 8
        /** No 0/O, 1/I/L: codes get read out loud and typed on phones. */
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** Accepts codes typed in lower case or with spaces or dashes. */
        fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
    }
}
