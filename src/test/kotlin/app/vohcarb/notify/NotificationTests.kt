package app.vohcarb.notify

import app.vohcarb.catalog.importer.FrequencyImporter
import app.vohcarb.catalog.importer.WordNetImporter
import app.vohcarb.devLogin
import app.vohcarb.ensureFixtureCatalog
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records what would be sent. Tokens starting with "dead-" are reported as uninstalled. */
class RecordingPushSender : PushSender {
    val sent = CopyOnWriteArrayList<Pair<String, PushMessage>>()

    override fun send(tokens: List<String>, message: PushMessage): PushResult {
        tokens.forEach { sent += it to message }
        val dead = tokens.filter { it.startsWith("dead-") }
        return PushResult(tokens.size - dead.size, dead)
    }

    fun to(token: String) = sent.filter { it.first == token }.map { it.second }
}

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(NotificationTests.Config::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: ObjectMapper,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val wordNet: WordNetImporter,
    @Autowired private val frequency: FrequencyImporter,
    @Autowired private val sender: RecordingPushSender,
    @Autowired private val reminders: ReminderService,
) {

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun recordingPushSender() = RecordingPushSender()
    }

    @BeforeAll
    fun catalog() = ensureFixtureCatalog(jdbc, wordNet, frequency)

    @BeforeEach
    fun clear() = sender.sent.clear()

    private fun newUser() = mockMvc.devLogin(json, "notify-${UUID.randomUUID()}@example.com")

    private fun register(token: String, device: String, platform: String = "ANDROID") = mockMvc.post("/api/v1/me/devices") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = """{"token": "$device", "platform": "$platform"}"""
    }

    private fun reminderAt(token: String, time: String, timezone: String) = mockMvc.put("/api/v1/me/preferences") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = """{"minDifficulty": 1, "maxDifficulty": 5, "wordsPerDay": 10, "timezone": "$timezone", "categories": [], "reminderTimes": ["$time"]}"""
    }.andExpect { status { isOk() } }

    private fun deviceCount(device: String) = jdbc.queryForObject("SELECT COUNT(*) FROM device_token WHERE token = ?", Long::class.java, device)

    @Test
    fun `devices register, list and unregister`() {
        val user = newUser()
        val device = "tok-${UUID.randomUUID()}"
        register(user, device).andExpect {
            status { isOk() }
            jsonPath("$.platform") { value("ANDROID") }
            jsonPath("$.tokenEnd") { value(device.takeLast(8)) }
        }
        mockMvc.get("/api/v1/me/devices") { header("Authorization", "Bearer $user") }.andExpect {
            jsonPath("$.length()") { value(1) }
        }
        mockMvc.delete("/api/v1/me/devices?token=$device") { header("Authorization", "Bearer $user") }.andExpect {
            status { isNoContent() }
        }
        assertEquals(0, deviceCount(device))
    }

    @Test
    fun `a phone moves to whoever signs in on it`() {
        val first = newUser()
        val second = newUser()
        val device = "tok-${UUID.randomUUID()}"
        register(first, device)
        register(second, device, "IOS")
        mockMvc.get("/api/v1/me/devices") { header("Authorization", "Bearer $first") }.andExpect { jsonPath("$.length()") { value(0) } }
        mockMvc.get("/api/v1/me/devices") { header("Authorization", "Bearer $second") }.andExpect {
            jsonPath("$[0].platform") { value("IOS") }
        }
        // Someone else can't unregister it.
        mockMvc.delete("/api/v1/me/devices?token=$device") { header("Authorization", "Bearer $first") }
        assertEquals(1, deviceCount(device))
    }

    @Test
    fun `test push reaches every device and drops uninstalled ones`() {
        val user = newUser()
        val alive = "tok-${UUID.randomUUID()}"
        val dead = "dead-${UUID.randomUUID()}"
        register(user, alive)
        register(user, dead)
        mockMvc.post("/api/v1/me/devices/test") { header("Authorization", "Bearer $user") }.andExpect {
            status { isOk() }
            jsonPath("$.devices") { value(2) }
            jsonPath("$.sent") { value(1) }
            jsonPath("$.removedDevices") { value(1) }
        }
        assertEquals("Vohcarb test", sender.to(alive).single().title)
        assertEquals(0, deviceCount(dead))
    }

    @Test
    fun `reminders go out once, at the user's local time`() {
        val user = newUser()
        val device = "tok-${UUID.randomUUID()}"
        register(user, device)
        reminderAt(user, "09:00", "Africa/Lagos") // UTC+1

        reminders.sendDue(Instant.parse("2030-01-10T07:59:00Z")) // 08:59 in Lagos: not yet
        assertTrue(sender.to(device).isEmpty())

        reminders.sendDue(Instant.parse("2030-01-10T08:03:00Z")) // 09:03: send
        reminders.sendDue(Instant.parse("2030-01-10T08:04:00Z")) // 09:04: already sent today
        val messages = sender.to(device)
        assertEquals(1, messages.size)
        assertTrue(messages[0].title.startsWith("Word of the day: "), messages[0].title) // nothing due, so word of the day
        assertEquals("word", messages[0].data["screen"])

        reminders.sendDue(Instant.parse("2030-01-11T08:20:00Z")) // next day but 20 minutes late: skipped
        assertEquals(1, sender.to(device).size)
    }

    @Test
    fun `reminders mention due reviews first`() {
        val user = newUser()
        val device = "tok-${UUID.randomUUID()}"
        register(user, device)
        reminderAt(user, "18:30", "UTC")
        mockMvc.patch("/api/v1/me/words/dog") {
            header("Authorization", "Bearer $user")
            contentType = MediaType.APPLICATION_JSON
            content = """{"saved": true}"""
        }
        mockMvc.post("/api/v1/me/practice/reviews") {
            header("Authorization", "Bearer $user")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reviews": [{"word": "dog", "grade": "GOOD", "reviewedAt": "${Instant.now().minus(Duration.ofDays(3))}"}]}"""
        }.andExpect { status { isOk() } }

        reminders.sendDue(Instant.parse("2030-02-01T18:31:00Z"))
        val message = sender.to(device).single()
        assertEquals("1 word is ready for review", message.title)
        assertEquals("practice", message.data["screen"])
    }

    @Test
    fun `users without a device get nothing`() {
        val user = newUser()
        reminderAt(user, "07:00", "UTC")
        val before = sender.sent.size
        reminders.sendDue(Instant.parse("2030-03-01T07:01:00Z"))
        assertEquals(before, sender.sent.size)
    }
}
