package com.movie.ticket_booking.event;

/** An admin marked the booking's refund as paid out. */
public record RefundCompletedEvent(Long bookingId) {
}
