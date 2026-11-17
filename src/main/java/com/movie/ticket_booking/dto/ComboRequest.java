package com.movie.ticket_booking.dto;

import jakarta.validation.constraints.*;

public record ComboRequest(
        @NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String nameEn,
        @Size(max = 255) String description,
        @Size(max = 255) String descriptionEn,
        @Size(max = 500) String imageUrl,
        @NotNull @Positive @Max(10_000_000) Long price,
        Boolean active,
        Integer sortOrder) {
}
