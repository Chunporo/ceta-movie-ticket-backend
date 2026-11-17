package com.movie.ticket_booking.dto.booking;

import java.util.List;

/** {@code combos} is optional (null or empty means no snacks). */
public record CreateBookingRequest(Long showtimeId, List<Long> seatIds, List<ComboLineRequest> combos) {
    public CreateBookingRequest(Long showtimeId, List<Long> seatIds) {
        this(showtimeId, seatIds, List.of());
    }
}
