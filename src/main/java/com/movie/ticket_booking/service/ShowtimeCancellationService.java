package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CancelShowtimeResponse;

/** D-22: cancel a showtime, refund its PAID bookings, drop its PENDING ones. */
public interface ShowtimeCancellationService {
    /** Idempotent: calling it again on a cancelled showtime finishes any bookings the first call did not reach. */
    CancelShowtimeResponse cancel(Long showtimeId, String reason, String adminEmail);
}
