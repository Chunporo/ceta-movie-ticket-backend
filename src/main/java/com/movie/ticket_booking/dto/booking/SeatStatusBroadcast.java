package com.movie.ticket_booking.dto.booking;

import java.util.List;

/**
 * STOMP payload on {@code /topic/showtime/{id}/seats} (architecture 8.5). Status only: no user ids,
 * no hold times. {@code seq} increases monotonically; clients drop older events.
 */
public record SeatStatusBroadcast(long seq, Long showtimeId, String at, List<Seat> seats) {
    public record Seat(Long seatId, String label, String status) {}
}
