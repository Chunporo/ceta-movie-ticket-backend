package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.MovieRequest;
import com.movie.ticket_booking.dto.MovieResponse;

import java.util.List;
import java.util.UUID;

public interface MovieService {
    List<MovieResponse> getAll();

    /** S4: every filter is optional; {@code genre} matches a genre's slug or name, ignoring case. */
    List<MovieResponse> search(String q, String genre, com.movie.ticket_booking.entity.enums.MovieStatus status);

    MovieResponse getById(UUID id);

    MovieResponse create(MovieRequest request);

    MovieResponse update(UUID id, MovieRequest request);

    void delete(UUID id);
}
