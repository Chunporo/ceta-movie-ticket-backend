package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;

public interface BookingCreationService {
    CreateBookingResponse create(String requesterEmail, CreateBookingRequest request);
}
