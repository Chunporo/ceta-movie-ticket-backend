package com.movie.ticket_booking.dto;

import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.PromotionKind;
import jakarta.validation.constraints.*;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;

public record PromotionRequest(
        @NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
        @NotBlank @Size(max = 100) String title,
        @Size(max = 100) String titleEn,
        @Size(max = 255) String description,
        @Size(max = 255) String descriptionEn,
        @Size(max = 500) String imageUrl,
        @NotNull PromotionKind kind,
        @Min(1) @Max(100) Integer percentOff,
        @Positive Long maxDiscount,
        @Positive Long fixedPrice,
        List<DayOfWeek> weekdays,
        List<MovieFormat> formats,
        boolean firstOrderOnly,
        boolean oncePerUser,
        Instant validFrom,
        Instant validUntil,
        Boolean active,
        Integer sortOrder) {
}
