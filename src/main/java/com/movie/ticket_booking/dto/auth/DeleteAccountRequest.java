package com.movie.ticket_booking.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeleteAccountRequest(@NotBlank @Size(max = 100) String password) {
}
