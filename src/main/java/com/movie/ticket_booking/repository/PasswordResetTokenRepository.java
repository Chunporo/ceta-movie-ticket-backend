package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    @EntityGraph(attributePaths = "user")
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** The one-use gate: only the caller that flips used_at from NULL gets 1. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now where t.id = :id and t.usedAt is null and t.expiresAt > :now")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);

    /** Retires every outstanding token of the user (a new request, a completed reset, a deleted account). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now where t.user.id = :userId and t.usedAt is null")
    int retireAll(@Param("userId") Long userId, @Param("now") Instant now);

    boolean existsByUserIdAndCreatedAtAfter(Long userId, Instant since);
}
