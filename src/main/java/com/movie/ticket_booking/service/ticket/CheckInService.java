package com.movie.ticket_booking.service.ticket;

import com.movie.ticket_booking.dto.booking.CheckInRequest;
import com.movie.ticket_booking.dto.booking.CheckInResponse;

public interface CheckInService {
    CheckInResponse checkIn(String staffEmail, CheckInRequest request);
}
