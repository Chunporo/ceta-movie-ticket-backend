package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.BookingExtrasRequest;
import com.movie.ticket_booking.dto.booking.BookingExtrasResponse;

public interface BookingExtrasService {
    /**
     * Replaces the snacks and the voucher of the caller's PENDING booking and recomputes its total from the frozen
     * seat prices. Refused once a payment attempt exists, so the amount VNPay was asked for never changes.
     */
    BookingExtrasResponse update(String requesterEmail, Long bookingId, BookingExtrasRequest request);
}
