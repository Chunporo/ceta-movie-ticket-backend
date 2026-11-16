package com.movie.ticket_booking.dto.booking;

import java.time.Instant;

/** A paid ticket as the customer app sees it. {@code qrPayload} is the string to render as a QR code. */
public record TicketResponse(Long ticketId, Long bookingId, String code, Long seatId, String seatLabel, Long price,
                             Showtime showtime, String qrPayload) {
    public record Showtime(Long showtimeId, Instant startTime, Instant endTime, String movieTitle, String roomName) {}
}
