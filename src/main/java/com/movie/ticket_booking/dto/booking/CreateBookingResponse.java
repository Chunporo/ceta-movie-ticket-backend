package com.movie.ticket_booking.dto.booking;

import java.time.Instant;
import java.util.List;

public record CreateBookingResponse(Long bookingId, Instant holdExpiresAt, Long totalAmount, List<Seat> seats) {
    public record Seat(Long seatId, String label, Long price) {}
}
