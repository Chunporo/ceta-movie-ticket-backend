package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.MovieSummaryResponse;

import java.util.List;
import java.util.UUID;

public interface ReminderService {
    /** "Remind me when tickets go on sale". Refused with 409 {@code ALREADY_ON_SALE} if a showtime already exists. */
    void add(String requesterEmail, UUID movieId);

    void remove(String requesterEmail, UUID movieId);

    /** Movies the caller is waiting for, newest first. */
    List<MovieSummaryResponse> mine(String requesterEmail);
}
