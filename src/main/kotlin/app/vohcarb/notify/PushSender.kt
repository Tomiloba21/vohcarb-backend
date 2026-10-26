package app.vohcarb.notify

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.nio.file.Files
import java.nio.file.Path

data class PushMessage(val title: String, val body: String, val data: Map<String, String> = emptyMap())

data class PushResult(val sent: Int, val invalidTokens: List<String>)

/** Delivers notifications to devices. Tokens reported invalid should be forgotten. */
interface PushSender {
    fun send(tokens: List<String>, message: PushMessage): PushResult
}

/** Development stand-in: writes notifications to the log instead of sending them. */
class LoggingPushSender : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(tokens: List<String>, message: PushMessage): PushResult {
        log.info("Push (not sent, FCM not configured) to {} device(s): {} | {} | {}", tokens.size, message.title, message.body, message.data)
        return PushResult(sent = tokens.size, invalidTokens = emptyList())
    }
}

/**
 * Firebase Cloud Messaging over the HTTP v1 API, one request per device.
 * FCM also delivers to iOS devices once the Firebase project has an APNs key.
 */
class FcmPushSender(
    private val http: RestClient,
    private val projectId: String,
    private val accessToken: () -> String,
) : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(tokens: List<String>, message: PushMessage): PushResult {
        var sent = 0
        val invalid = mutableListOf<String>()
        for (token in tokens) {
            val body = mapOf(
                "message" to mapOf(
                    "token" to token,
                    "notification" to mapOf("title" to message.title, "body" to message.body),
                    "data" to message.data,
                ),
            )
            try {
                http.post()
                    .uri("/v1/projects/{project}/messages:send", projectId)
                    .header("Authorization", "Bearer ${accessToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                sent++
            } catch (e: RestClientResponseException) {
                if (isDeadToken(e.statusCode, e.responseBodyAsString)) invalid += token
                else log.warn("FCM send failed with {}: {}", e.statusCode, e.responseBodyAsString.take(300))
            }
        }
        return PushResult(sent, invalid)
    }

    /** FCM answers 404 UNREGISTERED for uninstalled apps and 400 INVALID_ARGUMENT for malformed tokens. */
    private fun isDeadToken(status: HttpStatusCode, body: String) =
        status.value() == 404 || (status.value() == 400 && "INVALID_ARGUMENT" in body && "token" in body.lowercase())

    companion object {
        const val FCM_URL = "https://fcm.googleapis.com"
        private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

        /** From a Firebase service-account JSON key (Project settings → Service accounts → Generate new private key). */
        fun fromServiceAccountFile(file: Path): FcmPushSender {
            val credentials = Files.newInputStream(file).use { GoogleCredentials.fromStream(it) }.createScoped(SCOPE)
            val projectId = (credentials as? ServiceAccountCredentials)?.projectId
                ?: error("$file is not a service-account key with a project_id")
            return FcmPushSender(RestClient.builder().baseUrl(FCM_URL).build(), projectId) {
                credentials.refreshIfExpired()
                credentials.accessToken!!.tokenValue
            }
        }
    }
}

@ConfigurationProperties("vohcarb.push")
data class PushProperties(
    /** Path to the Firebase service-account JSON. Unset: notifications are only logged. */
    val fcmCredentialsFile: String? = null,
)

@Configuration
@EnableConfigurationProperties(PushProperties::class)
class PushConfig {

    @Bean
    fun pushSender(props: PushProperties): PushSender {
        val file = props.fcmCredentialsFile?.takeIf { it.isNotBlank() } ?: return LoggingPushSender()
        return FcmPushSender.fromServiceAccountFile(Path.of(file))
    }
}
