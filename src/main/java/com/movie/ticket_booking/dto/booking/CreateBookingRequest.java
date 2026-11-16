package com.movie.ticket_booking.dto.booking;

import java.util.List;

public record CreateBookingRequest(Long showtimeId, List<Long> seatIds) {
}
