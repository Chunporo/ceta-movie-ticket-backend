package com.movie.ticket_booking.dto.booking;

import com.movie.ticket_booking.entity.enums.BookingStatus;

import java.time.Instant;
import java.util.List;

public record BookingDetailResponse(BookingStatus status, Instant holdExpiresAt, long secondsRemaining,
                                    Long totalAmount, List<Seat> seats, List<Ticket> tickets) {
    public record Seat(Long seatId, String label, Long price) {}

    public record Ticket(Long ticketId, Long seatId, String label, String code, Long price) {}
}
