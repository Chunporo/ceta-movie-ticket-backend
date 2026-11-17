package com.movie.ticket_booking.dto.booking;

import java.time.Instant;

public record NotificationResponse(Long id, String type, String title, String body, Long bookingId,
                                   Instant createdAt, boolean read) {
}
