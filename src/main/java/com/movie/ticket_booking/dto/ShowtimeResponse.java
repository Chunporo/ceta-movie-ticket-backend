package com.movie.ticket_booking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowtimeResponse {

    private Long id;

    private MovieSummaryResponse movie;

    private RoomSummaryResponse room;
    private CinemaSummaryResponse cinema;

    private Instant startTime;

    private Instant endTime;

    private BigDecimal basePrice;

    private com.movie.ticket_booking.entity.enums.MovieFormat format;
    private com.movie.ticket_booking.entity.enums.LanguageMode languageMode;
    private String status;

    /** Seats free right now and seats that can be sold at all; null when the showtime has no inventory yet. */
    private Integer availableSeats;
    private Integer totalSeats;

    private String cancelReason;

    private Instant createdAt;

    private Instant updatedAt;
}
