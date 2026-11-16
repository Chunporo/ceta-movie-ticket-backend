package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.ShowtimeSeat;
import com.movie.ticket_booking.entity.ShowtimeSeatId;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Access is restricted to service.inventory by InventoryBoundaryTest. */
public interface ShowtimeSeatRepository extends Repository<ShowtimeSeat, ShowtimeSeatId> {
    @Query(value = """
            SELECT COUNT(*) FROM showtime_seat
            WHERE showtime_id=:showtimeId AND seat_id IN (:seatIds) AND status='AVAILABLE'
            """, nativeQuery = true)
    long countAvailable(@Param("showtimeId") Long showtimeId, @Param("seatIds") List<Long> seatIds);

    @Query(value = """
            SELECT seat_id FROM showtime_seat
            WHERE showtime_id=:showtimeId AND seat_id IN (:seatIds) AND status='AVAILABLE'
            """, nativeQuery = true)
    List<Long> findAvailableSeatIds(@Param("showtimeId") Long showtimeId, @Param("seatIds") List<Long> seatIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE showtime_seat SET status='HELD', held_until=:expiresAt,
                booking_id=:bookingId, updated_at=:now
            WHERE showtime_id=:showtimeId AND seat_id IN (:seatIds) AND status='AVAILABLE'
            """, nativeQuery = true)
    int claimAvailable(@Param("showtimeId") Long showtimeId, @Param("seatIds") List<Long> seatIds,
                       @Param("bookingId") Long bookingId, @Param("expiresAt") Instant expiresAt,
                       @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE showtime_seat SET status='AVAILABLE', held_until=NULL, booking_id=NULL, updated_at=:now
            WHERE booking_id=:bookingId AND status='HELD'
            """, nativeQuery = true)
    int releaseHeld(@Param("bookingId") Long bookingId, @Param("now") Instant now);

    @Query(value = "SELECT seat_id FROM showtime_seat WHERE booking_id=:bookingId AND status='HELD' ORDER BY seat_id",
            nativeQuery = true)
    List<Long> findHeldSeatIds(@Param("bookingId") Long bookingId);

    @Query(value = """
            SELECT COUNT(*) FROM showtime_seat
            WHERE booking_id=:bookingId AND status='HELD' AND held_until >= :now
            """, nativeQuery = true)
    long countPromotable(@Param("bookingId") Long bookingId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE showtime_seat SET status='SOLD', held_until=NULL, booking_id=NULL, updated_at=:now
            WHERE booking_id=:bookingId AND status='HELD' AND held_until >= :now
            """, nativeQuery = true)
    int promoteHeld(@Param("bookingId") Long bookingId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE showtime_seat SET status='AVAILABLE', held_until=NULL, booking_id=NULL, updated_at=:now
            WHERE status='HELD' AND held_until < :now
            """, nativeQuery = true)
    int releaseExpiredHeld(@Param("now") Instant now);

    @Query(value = "SELECT seat_id, status FROM showtime_seat WHERE showtime_id=:showtimeId", nativeQuery = true)
    List<Object[]> findStatuses(@Param("showtimeId") Long showtimeId);

    /** (showtime_id, seat_id) of the booking's live holds, row-locked so what we report is what we change. */
    @Query(value = """
            SELECT showtime_id, seat_id FROM showtime_seat
            WHERE booking_id=:bookingId AND status='HELD' ORDER BY showtime_id, seat_id FOR UPDATE
            """, nativeQuery = true)
    List<Object[]> lockHeldRefs(@Param("bookingId") Long bookingId);

    @Query(value = """
            SELECT showtime_id, seat_id FROM showtime_seat
            WHERE status='HELD' AND held_until < :now ORDER BY showtime_id, seat_id FOR UPDATE
            """, nativeQuery = true)
    List<Object[]> lockExpiredRefs(@Param("now") Instant now);

    @Query(value = "SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=:showtimeId AND status IN ('HELD','SOLD')", nativeQuery = true)
    long countInUse(@Param("showtimeId") Long showtimeId);

    // No clearAutomatically: callers (the showtime service) still hold lazily loaded entities in this session.
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM showtime_seat WHERE showtime_id=:showtimeId", nativeQuery = true)
    int deleteForShowtime(@Param("showtimeId") Long showtimeId);

    /** One row per seat of the showtime's room; seats that already have a row are left alone. */
    // No clearAutomatically: callers (the showtime service) still hold lazily loaded entities in this session.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO showtime_seat (showtime_id, seat_id, status, updated_at)
            SELECT sh.id, s.id, CASE WHEN s.active THEN 'AVAILABLE' ELSE 'BLOCKED' END, :now
            FROM showtime sh JOIN seat s ON s.room_id = sh.room_id
            WHERE sh.id = :showtimeId
              AND NOT EXISTS (SELECT 1 FROM showtime_seat x WHERE x.showtime_id = sh.id AND x.seat_id = s.id)
            """, nativeQuery = true)
    int initializeForShowtime(@Param("showtimeId") Long showtimeId, @Param("now") Instant now);
}
