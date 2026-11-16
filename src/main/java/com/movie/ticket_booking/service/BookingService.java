package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.*;

import java.util.List;

public interface BookingService {

    List<ShowtimeSeatStatusResponse> getShowtimeSeats(Long showtimeId, String requesterEmail);



    BookingCheckoutResponse checkout(String requesterEmail, CheckoutRequest request);

    List<BookingHistoryResponse> getUserBookings(String requesterEmail);
}
