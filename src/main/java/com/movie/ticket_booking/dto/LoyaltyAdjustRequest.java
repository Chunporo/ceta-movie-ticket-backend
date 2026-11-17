package com.movie.ticket_booking.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A support correction: {@code points} may be negative. A note is required so every correction can be explained later. */
public record LoyaltyAdjustRequest(@NotBlank @Email String email, @NotNull Integer points, @NotBlank @Size(max = 255) String note) {
}
