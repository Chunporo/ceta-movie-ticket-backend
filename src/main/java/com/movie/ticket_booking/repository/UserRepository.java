package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"roles"})
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = {"roles"})
    Optional<User> findByFirebaseUid(String firebaseUid);

    /**
     * Locks the customer's row. Taken before any booking lock (account deletion locks in the same order), it serialises
     * everything that spends the customer's CETA points.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    /**
     * One atomic UPDATE: counts the failure and, on the {@code max}-th consecutive one, locks the account and
     * restarts the count. locked_until is assigned first because MySQL evaluates SET left to right and would
     * otherwise see the already-incremented counter.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE users SET
              locked_until = CASE WHEN failed_login_attempts + 1 >= :max THEN :lockUntil ELSE locked_until END,
              failed_login_attempts = CASE WHEN failed_login_attempts + 1 >= :max THEN 0 ELSE failed_login_attempts + 1 END
            WHERE id = :id
            """, nativeQuery = true)
    int registerLoginFailure(@Param("id") Long id, @Param("max") int max, @Param("lockUntil") Instant lockUntil);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.failedLoginAttempts = 0, u.lockedUntil = null where u.id = :id")
    int clearLoginFailures(@Param("id") Long id);

    @Query("select u.lockedUntil from User u where u.id = :id")
    Optional<Instant> findLockedUntil(@Param("id") Long id);
}
