package com.movie.ticket_booking.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelShowtimeRequest(@NotBlank @Size(max = 500) String reason) {
}
