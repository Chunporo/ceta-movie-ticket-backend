package com.movie.ticket_booking.dto;

import com.movie.ticket_booking.entity.enums.AgeRating;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.MovieStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovieRequest {

    @NotBlank
    private String title;

    @NotBlank
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "slug must be lowercase and hyphen-separated")
    private String slug;

    @NotBlank
    private String posterUrl;

    @NotNull
    private AgeRating ageRating;

    @NotNull
    private MovieStatus status;

    @NotNull
    @Positive
    private Integer duration;

    private LocalDate releaseDate;

    private String trailerUrl;

    @jakarta.validation.constraints.Size(max = 100)
    private String director;

    @jakarta.validation.constraints.Size(max = 500)
    private String directorPhotoUrl;

    @jakarta.validation.constraints.Size(max = 500)
    private String backdropUrl;

    @jakarta.validation.constraints.Size(max = 10)
    private String language;

    private String description;

    @DecimalMin(value = "0.0")
    @DecimalMax(value = "5.0")
    private BigDecimal ratingScore;

    /** Names only (kept for older clients). Ignored when {@link #castMembers} is not empty. */
    @Builder.Default
    private List<@NotBlank String> cast = List.of();

    /** Cast with character and photo; takes precedence over {@link #cast}. */
    @Builder.Default
    @jakarta.validation.Valid
    private List<CastMemberDto> castMembers = List.of();

    @Builder.Default
    private Set<MovieFormat> formats = Set.of();

    @Builder.Default
    private List<@NotNull Long> genreIds = List.of();
}
