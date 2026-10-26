package app.vohcarb.notify

import app.vohcarb.user.now
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class Platform { ANDROID, IOS }

@Entity
@Table(name = "device_token")
class DeviceToken(
    var userId: Long,
    val token: String,
    @Enumerated(EnumType.STRING) var platform: Platform,
    val createdAt: Instant = now(),
    var lastSeenAt: Instant = now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
)

@Embeddable
data class ReminderLogId(val userId: Long = 0, val localDate: LocalDate = LocalDate.MIN, val remindAt: LocalTime = LocalTime.MIN) :
    Serializable

@Entity
@Table(name = "reminder_log")
class ReminderLog(@EmbeddedId val id: ReminderLogId, val sentAt: Instant = now())

interface DeviceTokenRepository : JpaRepository<DeviceToken, Long> {

    fun findByToken(token: String): DeviceToken?

    fun findByUserId(userId: Long): List<DeviceToken>

    fun deleteByToken(token: String)
}

interface ReminderLogRepository : JpaRepository<ReminderLog, ReminderLogId>
