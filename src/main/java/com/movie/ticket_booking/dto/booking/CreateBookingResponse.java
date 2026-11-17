package com.movie.ticket_booking.dto.booking;

import java.time.Instant;
import java.util.List;

/** {@code totalAmount} = seats + {@code comboAmount} - {@code discountAmount}. */
public record CreateBookingResponse(Long bookingId, Instant holdExpiresAt, Long totalAmount, List<Seat> seats,
                                    Long comboAmount, List<ComboLineResponse> combos,
                                    Long discountAmount, String voucherCode) {
    public CreateBookingResponse(Long bookingId, Instant holdExpiresAt, Long totalAmount, List<Seat> seats) {
        this(bookingId, holdExpiresAt, totalAmount, seats, 0L, List.of(), 0L, null);
    }

    public CreateBookingResponse(Long bookingId, Instant holdExpiresAt, Long totalAmount, List<Seat> seats,
                                 Long comboAmount, List<ComboLineResponse> combos) {
        this(bookingId, holdExpiresAt, totalAmount, seats, comboAmount, combos, 0L, null);
    }

    public record Seat(Long seatId, String label, Long price) {}
}
