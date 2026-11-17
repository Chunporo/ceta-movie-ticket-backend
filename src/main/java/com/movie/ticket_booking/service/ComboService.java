package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.ComboRequest;
import com.movie.ticket_booking.dto.ComboResponse;

import java.util.List;

public interface ComboService {
    /** Active combos in display order. */
    List<ComboResponse> list();

    ComboResponse create(ComboRequest request);

    ComboResponse update(Long id, ComboRequest request);
}
