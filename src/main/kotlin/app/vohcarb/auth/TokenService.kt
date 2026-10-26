package app.vohcarb.auth

import app.vohcarb.user.AppUser
import app.vohcarb.user.MeDto
import app.vohcarb.user.toMe
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Instant

data class TokenResponse(val accessToken: String, val tokenType: String, val expiresIn: Long, val user: MeDto)

@Service
@ConditionalOnWebApplication
class TokenService(private val encoder: JwtEncoder, private val props: AuthProperties) {

    /** Issues our own access token. Every sign-in method (dev now, Google later) ends here. */
    fun issue(user: AppUser, now: Instant = Instant.now()): TokenResponse {
        val claims = JwtClaimsSet.builder()
            .issuer(props.issuer)
            .subject(user.id.toString())
            .issuedAt(now)
            .expiresAt(now.plus(props.tokenTtl))
            .claim("name", user.displayName)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return TokenResponse(token, "Bearer", props.tokenTtl.seconds, user.toMe())
    }
}

/** The signed-in user's id, from a token issued by [TokenService]. */
val Jwt.userId: Long get() = subject!!.toLong()
