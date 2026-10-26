package app.vohcarb.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AppUserRepository : JpaRepository<AppUser, Long> {

    fun findByEmail(email: String): AppUser?
}

interface UserIdentityRepository : JpaRepository<UserIdentity, Long> {

    @Query("SELECT i FROM UserIdentity i JOIN FETCH i.user WHERE i.provider = :provider AND i.subject = :subject")
    fun findWithUser(provider: String, subject: String): UserIdentity?
}

interface UserPreferencesRepository : JpaRepository<UserPreferences, Long>
