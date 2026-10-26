package app.vohcarb.user

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class MeDto(val id: Long, val email: String?, val displayName: String, val createdAt: Instant)

data class PreferencesDto(
    val minDifficulty: Int,
    val maxDifficulty: Int,
    val wordsPerDay: Int,
    val timezone: String,
    /** Category slugs for the feed; empty means all categories. */
    val categories: List<String>,
    /** Local times of day ("HH:mm") for reminders. */
    val reminderTimes: List<String>,
)

/** Full replacement of the user's preferences (PUT semantics). */
data class UpdatePreferencesRequest(
    @field:Min(1) @field:Max(5) val minDifficulty: Int,
    @field:Min(1) @field:Max(5) val maxDifficulty: Int,
    @field:Min(1) @field:Max(50) val wordsPerDay: Int,
    @field:NotBlank val timezone: String,
    @field:Size(max = 40) val categories: List<String> = emptyList(),
    @field:Size(max = 5) val reminderTimes: List<LocalTime> = emptyList(),
)

private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

fun AppUser.toMe() = MeDto(id!!, email, displayName, createdAt)

fun UserPreferences.toDto() = PreferencesDto(
    minDifficulty = minDifficulty.toInt(),
    maxDifficulty = maxDifficulty.toInt(),
    wordsPerDay = wordsPerDay.toInt(),
    timezone = timezone,
    categories = categories.map { it.slug }.sorted(),
    reminderTimes = reminderTimes.sorted().map { it.format(HOUR_MINUTE) },
)
