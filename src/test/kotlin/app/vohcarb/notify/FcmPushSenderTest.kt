package app.vohcarb.notify

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

class FcmPushSenderTest {

    private val builder = RestClient.builder().baseUrl(FcmPushSender.FCM_URL)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val sender = FcmPushSender(builder.build(), "vohcarb-test") { "access-token" }

    private val url = "${FcmPushSender.FCM_URL}/v1/projects/vohcarb-test/messages:send"

    @Test
    fun `sends one v1 message per device and reports uninstalled ones`() {
        server.expect(requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer access-token"))
            .andExpect(jsonPath("$.message.token").value("good"))
            .andExpect(jsonPath("$.message.notification.title").value("Hi"))
            .andExpect(jsonPath("$.message.data.screen").value("word"))
            .andRespond(withSuccess("""{"name": "projects/vohcarb-test/messages/1"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo(url))
            .andExpect(jsonPath("$.message.token").value("gone"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("""{"error": {"status": "NOT_FOUND", "details": [{"errorCode": "UNREGISTERED"}]}}"""))
        server.expect(requestTo(url))
            .andExpect(jsonPath("$.message.token").value("flaky"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        val result = sender.send(listOf("good", "gone", "flaky"), PushMessage("Hi", "There", mapOf("screen" to "word")))

        server.verify()
        assertEquals(1, result.sent)
        assertEquals(listOf("gone"), result.invalidTokens) // a server hiccup is not a dead device
    }
}
