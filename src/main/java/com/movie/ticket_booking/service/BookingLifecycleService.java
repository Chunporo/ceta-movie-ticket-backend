package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.BookingDetailResponse;

public interface BookingLifecycleService {
    BookingDetailResponse get(String requesterEmail, Long bookingId);

    /** Releases the held seats and cancels a PENDING booking owned by the requester. */
    void cancel(String requesterEmail, Long bookingId);
}
