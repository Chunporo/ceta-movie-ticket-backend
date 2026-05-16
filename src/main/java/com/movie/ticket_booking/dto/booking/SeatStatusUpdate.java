package com.movie.ticket_booking.dto.booking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatStatusUpdate {
    private Long showtimeId;
    private List<Long> seatIds;
    private String status; // HELD, AVAILABLE, BOOKED
}
