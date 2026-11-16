package com.movie.ticket_booking.dto.booking;

import com.movie.ticket_booking.entity.enums.BookingStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@Builder
public class BookingHistoryResponse {
    private Long id;
    private BookingStatus status;
    private Long totalAmount;
    private Instant createdAt;

    private Long showtimeId;
    private Instant showDate; // Actually just use startTime for showDate in FE
    private Instant startTime;
    private Instant endTime;

    private java.util.UUID movieId;
    private String movieTitle;
    private String moviePosterUrl;

    private String roomName;

    private List<String> seatNames;
}
