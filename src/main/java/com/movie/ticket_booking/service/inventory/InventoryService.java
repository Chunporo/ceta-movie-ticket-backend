package com.movie.ticket_booking.service.inventory;

import java.time.Instant;
import java.util.List;

/**
 * Participates in the caller's transaction. Booking creation and status transitions
 * belong to the booking/payment use cases and must commit with inventory changes.
 */
public interface InventoryService {
    List<Long> claim(Long showtimeId, List<Long> seatIds, Long bookingId, Instant expiresAt);
    void release(Long bookingId);
    void promoteToSold(Long bookingId);
    int releaseExpired();
}
