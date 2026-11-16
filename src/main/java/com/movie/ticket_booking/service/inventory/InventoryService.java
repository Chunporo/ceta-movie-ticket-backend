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

    /**
     * All-or-nothing HELD -> SOLD for a payment callback. Returns false, changing nothing and without
     * marking the caller's transaction rollback-only, when fewer than {@code expectedSeats} holds are still
     * live (expired, swept or released); the caller then queues a refund.
     */
    boolean promoteAllToSold(Long bookingId, int expectedSeats);
    int releaseExpired();

    /** Non-locking read: the requested seats that are not AVAILABLE (sold, held, blocked) right now. */
    List<Long> unavailableSeats(Long showtimeId, List<Long> seatIds);

    /**
     * Kill-switch path (hold disabled): claim straight from AVAILABLE to SOLD at payment time. Returns false,
     * changing nothing and without marking the caller's transaction rollback-only, if any seat is not AVAILABLE.
     */
    boolean claimAndSell(Long showtimeId, List<Long> seatIds, Long bookingId);
}
