package com.movie.ticket_booking.event;

/** A PAID booking was refunded because its showtime was cancelled; the customer is emailed after commit. */
public record ShowtimeCancelledRefundEvent(Long bookingId, String reason) {
}
