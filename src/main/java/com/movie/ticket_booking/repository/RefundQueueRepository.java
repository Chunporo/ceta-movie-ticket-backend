package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.RefundQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefundQueueRepository extends JpaRepository<RefundQueue, Long> {
    boolean existsByBookingId(Long bookingId);
}
