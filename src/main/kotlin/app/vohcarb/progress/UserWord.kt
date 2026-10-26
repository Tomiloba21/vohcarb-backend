package app.vohcarb.progress

import app.vohcarb.catalog.Word
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.data.domain.Limit
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(name = "user_word")
class UserWord(
    val userId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "word_id")
    val word: Word,

    var seen: Boolean = false,
    var liked: Boolean = false,
    var saved: Boolean = false,
    var known: Boolean = false,
    var changedAt: Instant,
    var revision: Long,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
)

interface UserWordRepository : JpaRepository<UserWord, Long> {

    fun findByUserIdAndWordId(userId: Long, wordId: Long): UserWord?

    fun findByUserIdAndWordIdIn(userId: Long, wordIds: Collection<Long>): List<UserWord>

    fun findByUserIdAndRevisionGreaterThanOrderByRevision(userId: Long, revision: Long, limit: Limit): List<UserWord>

    fun findByUserIdAndSavedTrue(userId: Long, pageable: Pageable): Page<UserWord>
    fun findByUserIdAndLikedTrue(userId: Long, pageable: Pageable): Page<UserWord>
    fun findByUserIdAndKnownTrue(userId: Long, pageable: Pageable): Page<UserWord>
    fun findByUserIdAndSeenTrue(userId: Long, pageable: Pageable): Page<UserWord>
}
