package com.movie.ticket_booking.dto.booking;

import com.movie.ticket_booking.entity.enums.MovieFormat;

import java.time.Instant;

/**
 * A paid ticket as the customer app sees it. {@code qrPayload} is the string to render as a QR code; {@code used} is true once
 * staff scanned it at the door.
 */
public record TicketResponse(Long ticketId, Long bookingId, String code, Long seatId, String seatLabel, Long price,
                             Showtime showtime, String qrPayload, boolean used) {
    public TicketResponse(Long ticketId, Long bookingId, String code, Long seatId, String seatLabel, Long price,
                          Showtime showtime, String qrPayload) {
        this(ticketId, bookingId, code, seatId, seatLabel, price, showtime, qrPayload, false);
    }

    public record Showtime(Long showtimeId, Instant startTime, Instant endTime, String movieTitle, String roomName,
                           String cinemaName, String cinemaAddress, MovieFormat format, String posterUrl) {
        public Showtime(Long showtimeId, Instant startTime, Instant endTime, String movieTitle, String roomName) {
            this(showtimeId, startTime, endTime, movieTitle, roomName, null, null, null, null);
        }
    }
}
