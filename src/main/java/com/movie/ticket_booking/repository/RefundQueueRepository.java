package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.RefundQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import com.movie.ticket_booking.entity.enums.RefundStatus;

@Repository
public interface RefundQueueRepository extends JpaRepository<RefundQueue, Long> {
    boolean existsByBookingId(Long bookingId);

    List<RefundQueue> findByStatusOrderByCreatedAtAsc(RefundStatus status);

    List<RefundQueue> findAllByOrderByCreatedAtAsc();

    /**
     * Portable form of {@code INSERT ... ON DUPLICATE KEY UPDATE id = id} (H2 in the unit tests does not
     * accept it). Callers serialise on the booking row; unique(booking_id) is the backstop.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO refund_queue (booking_id, reason, amount, status, note, created_at)
            SELECT :bookingId, :reason, :amount, 'PENDING', :note, :now FROM DUAL
            WHERE NOT EXISTS (SELECT 1 FROM refund_queue WHERE booking_id = :bookingId)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("bookingId") Long bookingId, @Param("reason") String reason,
                       @Param("amount") Long amount, @Param("note") String note, @Param("now") Instant now);
}
