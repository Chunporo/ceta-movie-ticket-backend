package com.movie.ticket_booking.dto.booking;

import java.time.Instant;

public record RefundResponse(Long id, Long bookingId, String reason, Long amount, String status, String note,
                             Instant createdAt, Instant doneAt) {
}
