package app.vohcarb.notify

import app.vohcarb.user.now
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class DeviceDto(val platform: Platform, val tokenEnd: String, val createdAt: Instant, val lastSeenAt: Instant)

data class TestPushResult(val devices: Int, val sent: Int, val removedDevices: Int)

@Service
@Transactional
class NotificationService(
    private val devices: DeviceTokenRepository,
    private val sender: PushSender,
) {

    /**
     * Registers this phone for the signed-in user. A token already known (the app re-registers on every start)
     * just moves to whoever is signed in now, which handles sign-out / sign-in as someone else on the same phone.
     */
    fun register(userId: Long, token: String, platform: Platform): DeviceDto {
        val device = devices.findByToken(token)?.apply {
            this.userId = userId
            this.platform = platform
            lastSeenAt = now()
        } ?: devices.save(DeviceToken(userId, token, platform))

        // Old phones linger; keep the most recently seen few.
        devices.findByUserId(userId).sortedByDescending { it.lastSeenAt }.drop(MAX_DEVICES).forEach { devices.delete(it) }
        return device.toDto()
    }

    fun unregister(userId: Long, token: String) {
        devices.findByToken(token)?.takeIf { it.userId == userId }?.let { devices.delete(it) }
    }

    @Transactional(readOnly = true)
    fun list(userId: Long): List<DeviceDto> = devices.findByUserId(userId).map { it.toDto() }

    /** Sends to all the user's devices and forgets the ones FCM says are gone. */
    fun send(userId: Long, message: PushMessage): TestPushResult {
        val tokens = devices.findByUserId(userId).map { it.token }
        if (tokens.isEmpty()) return TestPushResult(0, 0, 0)
        val result = sender.send(tokens, message)
        result.invalidTokens.forEach { devices.deleteByToken(it) }
        return TestPushResult(tokens.size, result.sent, result.invalidTokens.size)
    }

    private fun DeviceToken.toDto() = DeviceDto(platform, token.takeLast(8), createdAt, lastSeenAt)

    private companion object {
        const val MAX_DEVICES = 10
    }
}
