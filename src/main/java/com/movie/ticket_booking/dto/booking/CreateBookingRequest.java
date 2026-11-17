package com.movie.ticket_booking.dto.booking;

import java.util.List;

/** {@code combos} and {@code voucherCode} are optional. */
public record CreateBookingRequest(Long showtimeId, List<Long> seatIds, List<ComboLineRequest> combos, String voucherCode) {
    public CreateBookingRequest(Long showtimeId, List<Long> seatIds) {
        this(showtimeId, seatIds, List.of(), null);
    }

    public CreateBookingRequest(Long showtimeId, List<Long> seatIds, List<ComboLineRequest> combos) {
        this(showtimeId, seatIds, combos, null);
    }
}
