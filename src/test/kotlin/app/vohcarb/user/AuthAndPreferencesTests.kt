package app.vohcarb.user

import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.emptyIterable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAndPreferencesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: ObjectMapper,
) {

    private fun login(email: String, name: String? = null): Pair<String, Long> {
        val body = mapOf("email" to email, "displayName" to name)
        val response = mockMvc.post("/api/v1/auth/dev-login") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(body)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val tree = json.readTree(response)
        return tree["accessToken"].asString() to tree["user"]["id"].asLong()
    }

    private fun putPreferences(token: String, body: Map<String, Any>) = mockMvc.put("/api/v1/me/preferences") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private val validPreferences = mapOf(
        "minDifficulty" to 3,
        "maxDifficulty" to 5,
        "wordsPerDay" to 15,
        "timezone" to "Africa/Lagos",
        "categories" to listOf("feelings", "food"),
        "reminderTimes" to listOf("20:30", "08:00"),
    )

    @Test
    fun `dev login creates a user once and returns a bearer token`() {
        val (token, id) = login("Ada@Example.com", "Ada")
        val (secondToken, sameId) = login("ada@example.com")
        assertEquals(id, sameId)
        assertNotEquals("", token)

        mockMvc.get("/api/v1/me") { header("Authorization", "Bearer $secondToken") }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value("ada@example.com") }
            jsonPath("$.displayName") { value("Ada") }
        }
    }

    @Test
    fun `display name defaults to the email's local part`() {
        val (token, _) = login("grace@example.com")
        mockMvc.get("/api/v1/me") { header("Authorization", "Bearer $token") }.andExpect {
            jsonPath("$.displayName") { value("grace") }
        }
    }

    @Test
    fun `me endpoints need a valid token`() {
        mockMvc.get("/api/v1/me").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/me") { header("Authorization", "Bearer not-a-real-token") }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `catalog stays public`() {
        mockMvc.get("/api/v1/categories").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/levels").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/words").andExpect { status { isOk() } }
    }

    @Test
    fun `invalid dev login is rejected`() {
        mockMvc.post("/api/v1/auth/dev-login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email": "not-an-email"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `new users get default preferences`() {
        val (token, _) = login("new@example.com")
        mockMvc.get("/api/v1/me/preferences") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.minDifficulty") { value(2) }
            jsonPath("$.maxDifficulty") { value(4) }
            jsonPath("$.wordsPerDay") { value(10) }
            jsonPath("$.timezone") { value("UTC") }
            jsonPath("$.categories") { value(emptyIterable<Any>()) }
            jsonPath("$.reminderTimes") { value(contains("09:00")) }
        }
    }

    @Test
    fun `preferences can be replaced and read back`() {
        val (token, _) = login("prefs@example.com")
        putPreferences(token, validPreferences).andExpect {
            status { isOk() }
            jsonPath("$.categories") { value(contains("feelings", "food")) }
            jsonPath("$.reminderTimes") { value(contains("08:00", "20:30")) }
        }
        mockMvc.get("/api/v1/me/preferences") { header("Authorization", "Bearer $token") }.andExpect {
            jsonPath("$.minDifficulty") { value(3) }
            jsonPath("$.maxDifficulty") { value(5) }
            jsonPath("$.wordsPerDay") { value(15) }
            jsonPath("$.timezone") { value("Africa/Lagos") }
            jsonPath("$.categories") { value(contains("feelings", "food")) }
        }
    }

    @Test
    fun `invalid preferences are rejected with a reason`() {
        val (token, _) = login("bad-prefs@example.com")
        putPreferences(token, validPreferences + ("minDifficulty" to 5) + ("maxDifficulty" to 2)).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("minDifficulty must not be above maxDifficulty") }
        }
        putPreferences(token, validPreferences + ("timezone" to "Mars/Olympus")).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("Unknown timezone 'Mars/Olympus'") }
        }
        putPreferences(token, validPreferences + ("categories" to listOf("feelings", "dragons"))).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("Unknown categories: dragons") }
        }
        putPreferences(token, validPreferences + ("wordsPerDay" to 500)).andExpect { status { isBadRequest() } }
        putPreferences(token, validPreferences + ("reminderTimes" to listOf("25:99"))).andExpect { status { isBadRequest() } }
    }
}

@SpringBootTest(properties = ["vohcarb.auth.dev-login-enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevLoginDisabledTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `dev login does not exist unless enabled`() {
        mockMvc.post("/api/v1/auth/dev-login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email": "someone@example.com"}"""
        }.andExpect { status { isNotFound() } }
    }
}
