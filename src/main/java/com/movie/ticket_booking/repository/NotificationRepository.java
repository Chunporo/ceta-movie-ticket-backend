package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** 1 when the notification is the user's (read or not), 0 otherwise; an already-read one keeps its first read time. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = coalesce(n.readAt, :now) where n.id = :id and n.userId = :userId")
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * One reminder per PAID booking whose show starts within the window. Set-based, so two instances (or two
     * runs) cannot double-send: the NOT EXISTS plus the unique key decide.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO notification (user_id, type, title, body, booking_id, created_at)
            SELECT b.user_id, 'SHOWTIME_REMINDER', :title, CONCAT(:bodyPrefix, b.id, :bodySuffix), b.id, :now
            FROM booking b JOIN showtime s ON s.id = b.showtime_id
            WHERE b.status = 'PAID' AND s.status = 'SCHEDULED' AND s.start_time > :now AND s.start_time <= :until
              AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.booking_id = b.id AND n.type = 'SHOWTIME_REMINDER' AND n.user_id = b.user_id)
            """, nativeQuery = true)
    int insertReminders(@Param("title") String title, @Param("bodyPrefix") String bodyPrefix,
                        @Param("bodySuffix") String bodySuffix, @Param("now") Instant now, @Param("until") Instant until);
}
