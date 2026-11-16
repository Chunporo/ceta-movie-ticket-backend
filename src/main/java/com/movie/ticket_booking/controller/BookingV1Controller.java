package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.booking.BookingDetailResponse;
import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.service.BookingCreationService;
import com.movie.ticket_booking.service.BookingLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingV1Controller {
    private final BookingCreationService bookingCreation;
    private final BookingLifecycleService lifecycle;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public CreateBookingResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateBookingRequest request) {
        return bookingCreation.create(jwt == null ? null : jwt.getSubject(), request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public BookingDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return lifecycle.get(jwt == null ? null : jwt.getSubject(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        lifecycle.cancel(jwt == null ? null : jwt.getSubject(), id);
    }
}
