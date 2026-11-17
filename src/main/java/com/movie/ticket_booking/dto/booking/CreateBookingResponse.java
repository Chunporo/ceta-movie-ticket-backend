package com.movie.ticket_booking.dto.booking;

import java.time.Instant;
import java.util.List;

/** {@code totalAmount} = seats + {@code comboAmount}. */
public record CreateBookingResponse(Long bookingId, Instant holdExpiresAt, Long totalAmount, List<Seat> seats,
                                    Long comboAmount, List<ComboLineResponse> combos) {
    public CreateBookingResponse(Long bookingId, Instant holdExpiresAt, Long totalAmount, List<Seat> seats) {
        this(bookingId, holdExpiresAt, totalAmount, seats, 0L, List.of());
    }

    public record Seat(Long seatId, String label, Long price) {}
}
