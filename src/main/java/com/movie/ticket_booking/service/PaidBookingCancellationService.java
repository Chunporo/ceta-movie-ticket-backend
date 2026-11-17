package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CancelPaidBookingResponse;

/** D-15: the customer cancels a PAID booking until 60 minutes before the show. PAID -> REFUNDED (never CANCELLED). */
public interface PaidBookingCancellationService {
    CancelPaidBookingResponse cancel(String requesterEmail, Long bookingId);
}
