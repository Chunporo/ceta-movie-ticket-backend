package com.movie.ticket_booking.event;

import com.movie.ticket_booking.entity.enums.RefundReason;

/** A refund row was newly queued for the booking (not published when it already existed). */
public record RefundQueuedEvent(Long bookingId, RefundReason reason) {
}
