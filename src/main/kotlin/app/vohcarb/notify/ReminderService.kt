package app.vohcarb.notify

import app.vohcarb.catalog.WordOfTheDayService
import app.vohcarb.practice.PracticeService
import app.vohcarb.user.UserPreferences
import app.vohcarb.user.now
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Sends each user's reminders at the times they chose, in their own timezone. A reminder counts as due for
 * [CATCH_UP] after its time, so a restart or a slow minute doesn't lose it; [ReminderLog] stops repeats.
 */
@Service
class ReminderService(
    private val em: EntityManager,
    private val logs: ReminderLogRepository,
    private val notifications: NotificationService,
    private val practice: PracticeService,
    private val wordOfTheDay: WordOfTheDayService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Returns how many reminders were sent. */
    @Transactional
    fun sendDue(now: Instant = now()): Int {
        val withDevices = em.createQuery(
            """
            SELECT DISTINCT p FROM UserPreferences p JOIN FETCH p.reminderTimes
            WHERE p.userId IN (SELECT d.userId FROM DeviceToken d)
            """.trimIndent(),
            UserPreferences::class.java,
        ).resultList

        var sent = 0
        for (prefs in withDevices) {
            val local = LocalDateTime.ofInstant(now, ZoneId.of(prefs.timezone))
            for (time in prefs.reminderTimes) {
                val sinceDue = Duration.between(time, local.toLocalTime())
                if (sinceDue.isNegative || sinceDue >= CATCH_UP) continue
                val id = ReminderLogId(prefs.userId, local.toLocalDate(), time)
                if (logs.existsById(id)) continue
                // Logged even if sending fails, so a broken device can't cause a retry every minute.
                logs.save(ReminderLog(id))
                try {
                    notifications.send(prefs.userId, reminderFor(prefs, local))
                    sent++
                } catch (e: Exception) {
                    log.warn("Reminder for user {} failed", prefs.userId, e)
                }
            }
        }
        return sent
    }

    /** Due reviews come first; otherwise the word of the day. */
    private fun reminderFor(prefs: UserPreferences, local: LocalDateTime): PushMessage {
        val stats = practice.stats(prefs.userId)
        if (stats.reviewsDue > 0) {
            val words = if (stats.reviewsDue == 1L) "1 word is" else "${stats.reviewsDue} words are"
            val body = if (stats.streakDays > 0) "Keep your ${stats.streakDays}-day streak going." else "A few minutes keeps them fresh."
            return PushMessage("$words ready for review", body, mapOf("type" to "reminder", "screen" to "practice"))
        }
        val word = wordOfTheDay.forDate(local.toLocalDate()).word
        val definition = word.senses.firstOrNull()?.definition.orEmpty()
        return PushMessage(
            "Word of the day: ${word.lemma}",
            if (definition.length > 120) definition.take(117) + "…" else definition,
            mapOf("type" to "reminder", "screen" to "word", "word" to word.slug),
        )
    }

    private companion object {
        val CATCH_UP: Duration = Duration.ofMinutes(15)
    }
}

/** Runs the reminder check every minute. Off in tests (vohcarb.reminders.enabled=false). */
@Component
@ConditionalOnBooleanProperty(name = ["vohcarb.reminders.enabled"], matchIfMissing = true)
class ReminderJob(private val reminders: ReminderService) {

    @Scheduled(cron = "0 * * * * *")
    fun run() {
        reminders.sendDue()
    }
}

@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty(name = ["vohcarb.reminders.enabled"], matchIfMissing = true)
class SchedulingConfig
