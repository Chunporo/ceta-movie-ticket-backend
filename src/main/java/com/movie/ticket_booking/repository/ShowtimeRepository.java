package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.Showtime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    @Query(value = "SELECT id FROM showtime WHERE id=:id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockByIdForInventory(@Param("id") Long id);

    /** Locks the row like {@link #lockByIdForInventory} and reports its status, so a claim can refuse a cancelled showtime. */
    @Query(value = "SELECT status FROM showtime WHERE id=:id FOR UPDATE", nativeQuery = true)
    Optional<String> lockStatusForInventory(@Param("id") Long id);

    /** SCHEDULED -> CANCELLED in one statement; 0 rows means it was already cancelled (or missing). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE showtime SET status='CANCELLED', cancelled_at=:now, cancel_reason=:reason, updated_at=:now
            WHERE id=:id AND status='SCHEDULED'
            """, nativeQuery = true)
    int markCancelled(@Param("id") Long id, @Param("reason") String reason, @Param("now") Instant now);

    List<Showtime> findByMovieId(UUID movieId);

    List<Showtime> findByRoomId(Long roomId);

        Optional<Showtime> findByRoomIdAndStartTime(Long roomId, Instant startTime);

    @Query("""
            select (count(s) > 0) from Showtime s
            where s.room.id = :roomId
              and s.startTime < :endTime
              and s.endTime > :startTime
              and (:excludeId is null or s.id <> :excludeId)
            """)
    boolean existsOverlappingInRoom(
            @Param("roomId") Long roomId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("excludeId") Long excludeId
    );
}
