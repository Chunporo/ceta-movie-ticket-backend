package com.movie.ticket_booking.dto.booking;

/** refunded = PAID bookings moved to REFUNDED (each queued for a manual refund); released = PENDING holds dropped. */
public record CancelShowtimeResponse(Long showtimeId, String status, int refunded, int released) {
}
