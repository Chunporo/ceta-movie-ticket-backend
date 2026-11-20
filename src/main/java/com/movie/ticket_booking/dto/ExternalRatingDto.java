package com.movie.ticket_booking.dto;

import java.math.BigDecimal;

/** One score from another site: {@code source} is IMDB, ROTTEN_TOMATOES, METACRITIC or TMDB; the score is {@code value} out of {@code scale} (10 or 100). */
public record ExternalRatingDto(String source, BigDecimal value, int scale, Long votes) {}
