package com.movie.ticket_booking.dto.booking;

import java.util.List;

public record SeatConflictResponse(String code, String message, List<SeatDetail> details) {
    public record SeatDetail(Long seatId) {}
}
