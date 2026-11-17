package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.*;

import java.util.List;

public interface BookingService {

    List<ShowtimeSeatStatusResponse> getShowtimeSeats(Long showtimeId, String requesterEmail);



    List<BookingHistoryResponse> getUserBookings(String requesterEmail);

    /** S11: newest first, 0-based page; size is capped at 50. */
    com.movie.ticket_booking.dto.booking.PageResponse<BookingHistoryResponse> getUserBookings(String requesterEmail, int page, int size);
}
