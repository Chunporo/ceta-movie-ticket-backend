package com.movie.ticket_booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code rating} 1..10 (10 = five full stars). */
public record ReviewRequest(@NotNull @Min(1) @Max(10) Integer rating, @Size(max = 1000) String comment) {
}
