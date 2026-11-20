package com.movie.ticket_booking.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/** The scores another site gives a film. Every field is optional; a missing one clears that score (this replaces them all). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExternalRatingsRequest {
    @Size(max = 20)
    private String imdbId;
    @DecimalMin("0.0") @DecimalMax("10.0")
    private BigDecimal imdbRating;
    @Min(0)
    private Long imdbVotes;
    @Min(0) @Max(100)
    private Integer rottenTomatoesPct;
    @Min(0) @Max(100)
    private Integer metascore;
    @DecimalMin("0.0") @DecimalMax("10.0")
    private BigDecimal tmdbRating;
    @Min(0)
    private Long tmdbVotes;
}
