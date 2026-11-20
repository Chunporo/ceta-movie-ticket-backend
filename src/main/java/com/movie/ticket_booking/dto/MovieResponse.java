package com.movie.ticket_booking.dto;

import com.movie.ticket_booking.entity.enums.AgeRating;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.MovieStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovieResponse {

    private UUID id;

    private String title;

    private String slug;

    private String posterUrl;

    private AgeRating ageRating;

    private MovieStatus status;

    private Integer duration;

    private LocalDate releaseDate;

    private String trailerUrl;
    private String director;
    private String directorPhotoUrl;
    private String language;

    private String description;

    private BigDecimal ratingScore;
    /** Average of customers' reviews on a 0..10 scale (one decimal), null when nobody has reviewed yet. */
    private Double ratingAverage;
    private long ratingCount;
    /** IMDb id (for a link), and the scores other sites give the film; empty when none was imported. */
    private String imdbId;
    private List<ExternalRatingDto> externalRatings;
    /** Cheapest base seat price among upcoming showtimes in VND, null when nothing is on sale. */
    private Long fromPrice;

    /** Names only, kept for older clients. */
    private List<String> cast;

    /** Cast with character and photo URL (role and photo may be null). */
    private List<CastMemberDto> castMembers;

    private Set<MovieFormat> formats;

    private List<GenreResponse> genres;

    private Instant createdAt;

    private Instant updatedAt;
}
