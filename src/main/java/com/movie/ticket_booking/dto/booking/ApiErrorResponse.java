package com.movie.ticket_booking.dto.booking;

import java.util.List;

public record ApiErrorResponse(String code, String message, List<Object> details) {
}
