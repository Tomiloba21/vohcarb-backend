package app.vohcarb.user

import app.vohcarb.catalog.CategoryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.DateTimeException
import java.time.ZoneId

@Service
@Transactional
class UserService(
    private val users: AppUserRepository,
    private val identities: UserIdentityRepository,
    private val preferences: UserPreferencesRepository,
    private val categories: CategoryRepository,
) {

    /**
     * Finds the user behind a provider identity, creating them on first sign-in.
     * A new identity whose email already belongs to a user is linked to that user, so signing in with Google
     * later reaches the same account as a dev login with the same address.
     */
    fun signIn(provider: String, subject: String, email: String?, displayName: String): AppUser {
        identities.findWithUser(provider, subject)?.let { return it.user }

        val user = email?.let { users.findByEmail(it) }
            ?: users.save(AppUser(email = email, displayName = displayName)).also {
                preferences.save(UserPreferences(userId = it.id!!))
            }
        identities.save(UserIdentity(user = user, provider = provider, subject = subject))
        return user
    }

    @Transactional(readOnly = true)
    fun me(userId: Long): MeDto = user(userId).toMe()

    @Transactional(readOnly = true)
    fun preferences(userId: Long): PreferencesDto = preferencesOf(userId).toDto()

    fun updatePreferences(userId: Long, request: UpdatePreferencesRequest): PreferencesDto {
        if (request.minDifficulty > request.maxDifficulty) badRequest("minDifficulty must not be above maxDifficulty")
        val zone = try {
            ZoneId.of(request.timezone).id
        } catch (_: DateTimeException) {
            badRequest("Unknown timezone '${request.timezone}'")
        }
        val slugs = request.categories.toSet()
        val chosen = categories.findAll().filter { it.slug in slugs }
        val unknown = slugs - chosen.map { it.slug }.toSet()
        if (unknown.isNotEmpty()) badRequest("Unknown categories: ${unknown.sorted().joinToString()}")

        val prefs = preferencesOf(userId).apply {
            minDifficulty = request.minDifficulty.toShort()
            maxDifficulty = request.maxDifficulty.toShort()
            wordsPerDay = request.wordsPerDay.toShort()
            timezone = zone
            categories.clear()
            categories.addAll(chosen)
            reminderTimes.clear()
            reminderTimes.addAll(request.reminderTimes.map { it.withSecond(0).withNano(0) })
            updatedAt = now()
        }
        return prefs.toDto()
    }

    private fun user(userId: Long): AppUser =
        users.findById(userId).orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists") }

    private fun preferencesOf(userId: Long): UserPreferences {
        user(userId)
        return preferences.findById(userId).orElseGet { preferences.save(UserPreferences(userId = userId)) }
    }

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
