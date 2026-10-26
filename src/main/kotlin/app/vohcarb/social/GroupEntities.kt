package app.vohcarb.social

import app.vohcarb.catalog.Word
import app.vohcarb.user.AppUser
import app.vohcarb.user.now
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.io.Serializable
import java.time.Instant

enum class GroupRole { OWNER, MEMBER }

@Entity
@Table(name = "friend_group")
class FriendGroup(
    var name: String,
    var inviteCode: String,
    val createdBy: Long,
    val createdAt: Instant = now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
)

@Embeddable
data class GroupMemberId(val groupId: Long = 0, val userId: Long = 0) : Serializable

@Entity
@Table(name = "group_member")
class GroupMember(
    @EmbeddedId val id: GroupMemberId,

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    val user: AppUser,

    @Enumerated(EnumType.STRING) var role: GroupRole,
    val joinedAt: Instant = now(),
)

@Entity
@Table(name = "group_word")
class GroupWord(
    val groupId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "word_id")
    val word: Word,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by")
    val addedBy: AppUser,

    val note: String?,
    val addedAt: Instant = now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
)

interface FriendGroupRepository : JpaRepository<FriendGroup, Long> {

    fun findByInviteCode(code: String): FriendGroup?

    fun existsByInviteCode(code: String): Boolean
}

interface GroupMemberRepository : JpaRepository<GroupMember, GroupMemberId> {

    @Query("SELECT m FROM GroupMember m JOIN FETCH m.user WHERE m.id.groupId = :groupId ORDER BY m.joinedAt")
    fun findMembers(groupId: Long): List<GroupMember>

    @Query("SELECT m FROM GroupMember m WHERE m.id.userId = :userId ORDER BY m.joinedAt")
    fun findByUser(userId: Long): List<GroupMember>

    @Query("SELECT COUNT(m) FROM GroupMember m WHERE m.id.groupId = :groupId")
    fun countMembers(groupId: Long): Long

    @Query("SELECT COUNT(m) FROM GroupMember m WHERE m.id.userId = :userId")
    fun countGroups(userId: Long): Long

    @Modifying
    @Query("DELETE FROM GroupMember m WHERE m.id.groupId = :groupId")
    fun deleteByGroup(groupId: Long)
}

interface GroupWordRepository : JpaRepository<GroupWord, Long> {

    fun findByGroupIdAndWordId(groupId: Long, wordId: Long): GroupWord?

    fun findByGroupId(groupId: Long, pageable: Pageable): Page<GroupWord>

    fun deleteByGroupId(groupId: Long)
}
