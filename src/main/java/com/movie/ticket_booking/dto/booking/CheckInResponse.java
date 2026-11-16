package com.movie.ticket_booking.dto.booking;

import java.time.Instant;

public record CheckInResponse(Long ticketId, String code, String seatLabel, String movieTitle, String roomName,
                              Instant startTime, Instant checkedInAt) {
}
