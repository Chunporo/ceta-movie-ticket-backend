package com.movie.ticket_booking.dto.booking;

/** Exactly one of {@code qr} (scanned) or {@code code} (typed in). */
public record CheckInRequest(String qr, String code) {
}
