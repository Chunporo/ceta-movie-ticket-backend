package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.BookingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface BookingStatusHistoryRepository extends JpaRepository<BookingStatusHistory, Long> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO booking_status_history (booking_id, from_status, to_status, reason, created_at)
            SELECT id, :from, :to, :reason, :now FROM booking WHERE id IN (:ids)
            """, nativeQuery = true)
    int insertForBookings(@Param("ids") List<Long> ids, @Param("from") String from, @Param("to") String to,
                          @Param("reason") String reason, @Param("now") Instant now);
}
