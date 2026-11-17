package com.movie.ticket_booking.dto.booking;

import com.movie.ticket_booking.entity.enums.BookingStatus;

import java.time.Instant;
import java.util.List;

/** {@code totalAmount} = seats + {@code comboAmount}. */
public record BookingDetailResponse(BookingStatus status, Instant holdExpiresAt, long secondsRemaining,
                                    Long totalAmount, List<Seat> seats, List<Ticket> tickets,
                                    Long id, String movieTitle, String roomName, Instant startTime, boolean refundRequired,
                                    Long comboAmount, List<ComboLineResponse> combos) {
    public BookingDetailResponse(BookingStatus status, Instant holdExpiresAt, long secondsRemaining,
                                 Long totalAmount, List<Seat> seats, List<Ticket> tickets,
                                 Long id, String movieTitle, String roomName, Instant startTime, boolean refundRequired) {
        this(status, holdExpiresAt, secondsRemaining, totalAmount, seats, tickets, id, movieTitle, roomName, startTime,
                refundRequired, 0L, List.of());
    }

    public record Seat(Long seatId, String label, Long price) {}
    public record Ticket(Long ticketId, Long seatId, String label, String code, Long price) {}
}
