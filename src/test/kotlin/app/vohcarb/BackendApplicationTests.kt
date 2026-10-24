package app.vohcarb

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendApplicationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    fun `flyway applies baseline migration`() {
        val owner = jdbc.queryForObject(
            "SELECT meta_value FROM app_meta WHERE meta_key = 'schema_owner'", String::class.java,
        )
        assertEquals("vohcarb", owner)
    }

    @Test
    fun `info endpoint reports api version`() {
        mockMvc.get("/api/v1/info").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("vohcarb-backend") }
            jsonPath("$.apiVersion") { value(1) }
        }
    }

    @Test
    fun `health endpoint is up`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }
}
