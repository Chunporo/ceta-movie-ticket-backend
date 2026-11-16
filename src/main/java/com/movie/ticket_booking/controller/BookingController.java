package com.movie.ticket_booking.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.movie.ticket_booking.dto.booking.*;
import com.movie.ticket_booking.service.BookingService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@Slf4j
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    public BookingCheckoutResponse checkout(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CheckoutRequest request
    ) {
        String email = jwt == null ? null : jwt.getSubject();
        log.info("POST /api/bookings/checkout showtimeId={} seatCount={} user={} provider={}",
                request.getShowtimeId(), request.getSeatIds().size(), email, request.getPaymentProvider());
        return bookingService.checkout(email, request);
    }
    @GetMapping("/my-bookings")
    @PreAuthorize("isAuthenticated()")
    public List<BookingHistoryResponse> getMyBookings(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt == null ? null : jwt.getSubject();
        log.info("GET /api/bookings/my-bookings user={}", email);
        return bookingService.getUserBookings(email);
    }
}
