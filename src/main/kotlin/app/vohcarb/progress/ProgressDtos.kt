package app.vohcarb.progress

import app.vohcarb.catalog.WordSummaryDto
import app.vohcarb.catalog.toSummary
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/** The four flags a user can have on a word. */
data class WordFlags(val seen: Boolean, val liked: Boolean, val saved: Boolean, val known: Boolean) {
    companion object {
        val NONE = WordFlags(seen = false, liked = false, saved = false, known = false)
    }
}

/** Online update of some flags; omitted fields keep their value. */
data class WordStatePatch(
    val seen: Boolean? = null,
    val liked: Boolean? = null,
    val saved: Boolean? = null,
    val known: Boolean? = null,
) {
    fun applyTo(current: WordFlags) = WordFlags(
        seen = seen ?: current.seen,
        liked = liked ?: current.liked,
        saved = saved ?: current.saved,
        known = known ?: current.known,
    )
}

data class MyWordDto(
    val word: WordSummaryDto,
    val seen: Boolean,
    val liked: Boolean,
    val saved: Boolean,
    val known: Boolean,
    /** Null when the user has never touched this word. */
    val changedAt: Instant?,
)

enum class WordList { SAVED, LIKED, KNOWN, SEEN }

/** A word's full state as the device last set it, offline or not. */
data class SyncChange(
    @field:NotBlank val word: String,
    val seen: Boolean,
    val liked: Boolean,
    val saved: Boolean,
    val known: Boolean,
    /** When the change happened on the device. */
    val changedAt: Instant,
)

data class SyncRequest(
    /** The `revision` from the previous sync response; 0 on first sync. */
    @field:Min(0) val sinceRevision: Long,
    @field:Size(max = 500) @field:Valid val changes: List<SyncChange> = emptyList(),
)

data class SyncedWordDto(
    val word: String,
    val seen: Boolean,
    val liked: Boolean,
    val saved: Boolean,
    val known: Boolean,
    val changedAt: Instant,
    val revision: Long,
)

data class SyncResponse(
    /** Send this as `sinceRevision` next time. */
    val revision: Long,
    /** True when more rows are waiting: sync again straight away with the new revision. */
    val hasMore: Boolean,
    val changes: List<SyncedWordDto>,
    /** Slugs in the request that aren't in the catalog; they were ignored. */
    val unknownWords: List<String>,
)

val UserWord.flags get() = WordFlags(seen, liked, saved, known)

fun UserWord.toMyWord() = MyWordDto(word.toSummary(), seen, liked, saved, known, changedAt)

fun UserWord.toSynced() = SyncedWordDto(word.slug, seen, liked, saved, known, changedAt, revision)
