package com.movie.ticket_booking.service;

import java.time.Instant;

public interface BookingExpiryService {
    /** Expires overdue PENDING bookings and, unless the kill switch is on, frees their lapsed seat holds. */
    SweepResult expireOverdue(Instant now);

    record SweepResult(int expiredBookings, int releasedSeats) {}
}
