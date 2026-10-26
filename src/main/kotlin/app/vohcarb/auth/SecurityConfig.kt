package app.vohcarb.auth

import com.nimbusds.jose.jwk.source.ImmutableSecret
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless API security. Clients sign in through an /api/v1/auth endpoint, get an access token signed by us,
 * and send it as `Authorization: Bearer <token>`. The word catalog stays public.
 */
@Configuration
@ConditionalOnWebApplication
@EnableConfigurationProperties(AuthProperties::class)
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class SecurityConfig(private val props: AuthProperties) {

    private val key: SecretKey = SecretKeySpec(props.jwtSecret.toByteArray(), "HmacSHA256")

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(key))

    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
        .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer)) }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() } // no cookies or sessions, so nothing for CSRF to abuse
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            headers { frameOptions { sameOrigin = true } } // H2 console uses frames
            authorizeHttpRequests {
                for (path in PUBLIC_GET) authorize(HttpMethod.GET, path, permitAll)
                authorize("/api/v1/auth/**", permitAll)
                authorize("/h2-console/**", permitAll)
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer { jwt { } }
        }
        return http.build()
    }

    private companion object {
        val PUBLIC_GET = listOf(
            "/api/v1/info",
            "/api/v1/categories",
            "/api/v1/levels",
            "/api/v1/words/**",
            "/api/v1/random-word",
            "/api/v1/word-of-the-day",
            "/actuator/health/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
        )
    }
}
