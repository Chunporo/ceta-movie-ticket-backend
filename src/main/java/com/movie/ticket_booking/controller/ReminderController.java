package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.MovieSummaryResponse;
import com.movie.ticket_booking.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** "Remind me when tickets go on sale" for coming-soon movies. */
@RestController
@RequiredArgsConstructor
public class ReminderController {
    private final ReminderService reminderService;

    @PutMapping({"/api/movies/{movieId}/reminder", "/api/v1/movies/{movieId}/reminder"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID movieId) {
        reminderService.add(jwt == null ? null : jwt.getSubject(), movieId);
    }

    @DeleteMapping({"/api/movies/{movieId}/reminder", "/api/v1/movies/{movieId}/reminder"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID movieId) {
        reminderService.remove(jwt == null ? null : jwt.getSubject(), movieId);
    }

    @GetMapping({"/api/reminders", "/api/v1/reminders"})
    @PreAuthorize("isAuthenticated()")
    public List<MovieSummaryResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return reminderService.mine(jwt == null ? null : jwt.getSubject());
    }
}
