package com.movie.ticket_booking.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(@NotBlank @Size(max = 100) String oldPassword,
                                    @NotBlank @Size(min = 8, max = 100) String newPassword) {
}
