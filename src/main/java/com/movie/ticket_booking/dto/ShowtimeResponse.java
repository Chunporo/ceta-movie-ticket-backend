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

    private String cancelReason;

    private Instant createdAt;

    private Instant updatedAt;
}
