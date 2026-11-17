package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.booking.CancelShowtimeRequest;
import com.movie.ticket_booking.dto.booking.CancelShowtimeResponse;
import com.movie.ticket_booking.service.ShowtimeCancellationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/showtimes")
@RequiredArgsConstructor
public class AdminShowtimeController {
    private final ShowtimeCancellationService cancellation;

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public CancelShowtimeResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelShowtimeRequest body,
                                         Authentication auth) {
        return cancellation.cancel(id, body.reason(), auth == null ? null : auth.getName());
    }
}
