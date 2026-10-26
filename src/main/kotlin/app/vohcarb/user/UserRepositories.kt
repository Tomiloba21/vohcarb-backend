package app.vohcarb.user

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface AppUserRepository : JpaRepository<AppUser, Long> {

    fun findByEmail(email: String): AppUser?

    /** Row lock so one user's concurrent syncs hand out revisions one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    fun lockById(id: Long): AppUser?
}

interface UserIdentityRepository : JpaRepository<UserIdentity, Long> {

    @Query("SELECT i FROM UserIdentity i JOIN FETCH i.user WHERE i.provider = :provider AND i.subject = :subject")
    fun findWithUser(provider: String, subject: String): UserIdentity?
}

interface UserPreferencesRepository : JpaRepository<UserPreferences, Long>
