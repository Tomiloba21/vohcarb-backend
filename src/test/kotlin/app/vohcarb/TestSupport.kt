package app.vohcarb

import app.vohcarb.catalog.importer.FrequencyImporter
import app.vohcarb.catalog.importer.WordNetImporter
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

/** Signs in through dev login and returns the access token. */
fun MockMvc.devLogin(json: ObjectMapper, email: String): String {
    val response = post("/api/v1/auth/dev-login") {
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("email" to email))
    }.andReturn().response.contentAsString
    return json.readTree(response)["accessToken"].asString()
}

/**
 * Loads the fixture catalog (dog, happy, joy at level 1; o'clock at level 5) unless a test class sharing
 * this database already did.
 */
fun ensureFixtureCatalog(jdbc: JdbcTemplate, wordNet: WordNetImporter, frequency: FrequencyImporter) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM word", Long::class.java) != 0L) return
    WordNetImporter::class.java.getResourceAsStream("/wordnet-fixture.xml")!!.use { wordNet.importStream(it, replace = false) }
    WordNetImporter::class.java.getResourceAsStream("/frequency-fixture.txt")!!.use { frequency.importStream(it) }
}
