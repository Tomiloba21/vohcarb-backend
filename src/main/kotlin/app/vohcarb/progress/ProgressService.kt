package app.vohcarb.progress

import app.vohcarb.catalog.PageResponse
import app.vohcarb.catalog.Word
import app.vohcarb.catalog.WordRepository
import app.vohcarb.catalog.toSummary
import app.vohcarb.user.AppUser
import app.vohcarb.user.AppUserRepository
import app.vohcarb.user.now
import org.springframework.data.domain.Limit
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Saved / liked / seen / known words, and syncing them with offline devices.
 *
 * Every change to a user's word gets the next value of that user's revision counter. A device remembers the
 * revision of its last sync and asks for everything above it, so pulls never depend on device clocks.
 * When two devices change the same word, the change with the later device time wins (last-write-wins).
 */
@Service
@Transactional
class ProgressService(
    private val users: AppUserRepository,
    private val words: WordRepository,
    private val userWords: UserWordRepository,
) {

    @Transactional(readOnly = true)
    fun state(userId: Long, slug: String): MyWordDto {
        val word = word(slug)
        return userWords.findByUserIdAndWordId(userId, word.id)?.toMyWord()
            ?: MyWordDto(word.toSummary(), seen = false, liked = false, saved = false, known = false, changedAt = null)
    }

    fun update(userId: Long, slug: String, patch: WordStatePatch): MyWordDto {
        val user = lockUser(userId)
        val word = word(slug)
        val existing = userWords.findByUserIdAndWordId(userId, word.id)
        val flags = patch.applyTo(existing?.flags ?: WordFlags.NONE)
        // An online change happens "now", but never sorts before what the row already has.
        val at = existing?.changedAt?.let { maxOf(it, now()) } ?: now()
        return write(user, word, existing, flags, at).toMyWord()
    }

    @Transactional(readOnly = true)
    fun list(userId: Long, list: WordList, page: Int, size: Int): PageResponse<MyWordDto> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "changedAt"))
        val result = when (list) {
            WordList.SAVED -> userWords.findByUserIdAndSavedTrue(userId, pageable)
            WordList.LIKED -> userWords.findByUserIdAndLikedTrue(userId, pageable)
            WordList.KNOWN -> userWords.findByUserIdAndKnownTrue(userId, pageable)
            WordList.SEEN -> userWords.findByUserIdAndSeenTrue(userId, pageable)
        }
        return PageResponse.of(result) { it.toMyWord() }
    }

    fun sync(userId: Long, request: SyncRequest): SyncResponse {
        val user = lockUser(userId)
        val serverNow = now()

        val bySlug = words.findAllBySlugIn(request.changes.map { it.word }.toSet()).associateBy { it.slug }
        val existing = userWords.findByUserIdAndWordIdIn(userId, bySlug.values.map { it.id })
            .associateBy { it.word.id }.toMutableMap()

        // If a device sends the same word twice, its latest change is the one that counts.
        for (change in request.changes.filter { it.word in bySlug }.sortedBy { it.changedAt }) {
            val word = bySlug.getValue(change.word)
            // A device clock ahead of ours must not make its changes win forever.
            val at = minOf(change.changedAt, serverNow)
            val row = existing[word.id]
            if (row != null && !at.isAfter(row.changedAt)) continue
            existing[word.id] = write(user, word, row, WordFlags(change.seen, change.liked, change.saved, change.known), at)
        }

        val pulled = userWords.findByUserIdAndRevisionGreaterThanOrderByRevision(userId, request.sinceRevision, Limit.of(PULL_LIMIT + 1))
        val hasMore = pulled.size > PULL_LIMIT
        val page = pulled.take(PULL_LIMIT)
        return SyncResponse(
            revision = if (hasMore) page.last().revision else user.syncRevision,
            hasMore = hasMore,
            changes = page.map { it.toSynced() },
            unknownWords = request.changes.map { it.word }.filter { it !in bySlug }.distinct(),
        )
    }

    private fun write(user: AppUser, word: Word, existing: UserWord?, flags: WordFlags, at: Instant): UserWord {
        val row = existing ?: UserWord(userId = user.id!!, word = word, changedAt = at, revision = 0)
        row.seen = flags.seen
        row.liked = flags.liked
        row.saved = flags.saved
        row.known = flags.known
        row.changedAt = at
        row.revision = ++user.syncRevision
        return userWords.save(row)
    }

    private fun lockUser(userId: Long): AppUser =
        users.lockById(userId) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists")

    private fun word(slug: String): Word =
        words.findBySlug(slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No word '$slug' found")

    private companion object {
        const val PULL_LIMIT = 1_000
    }
}
