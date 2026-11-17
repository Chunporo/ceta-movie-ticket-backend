package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.CinemaRequest;
import com.movie.ticket_booking.dto.CinemaResponse;

import java.util.List;

public interface CinemaService {
    /** Active cinemas, optionally of one city (case-insensitive). */
    List<CinemaResponse> list(String city);

    CinemaResponse get(Long id);

    CinemaResponse create(CinemaRequest request);

    CinemaResponse update(Long id, CinemaRequest request);
}
