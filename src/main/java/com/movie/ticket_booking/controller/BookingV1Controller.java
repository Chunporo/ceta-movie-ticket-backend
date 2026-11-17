package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.booking.BookingDetailResponse;
import com.movie.ticket_booking.dto.booking.BookingExtrasRequest;
import com.movie.ticket_booking.dto.booking.BookingExtrasResponse;
import com.movie.ticket_booking.service.BookingExtrasService;
import com.movie.ticket_booking.dto.booking.CancelPaidBookingResponse;
import com.movie.ticket_booking.service.PaidBookingCancellationService;
import com.movie.ticket_booking.service.BookingService;
import com.movie.ticket_booking.dto.booking.BookingHistoryResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
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
    private final PaidBookingCancellationService paidCancellation;
    private final BookingService bookingService;
    private final BookingExtrasService extrasService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public CreateBookingResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateBookingRequest request) {
        return bookingCreation.create(jwt == null ? null : jwt.getSubject(), request);
    }

    /** S11: the customer's bookings, newest first. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<BookingHistoryResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return bookingService.getUserBookings(jwt == null ? null : jwt.getSubject(), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public BookingDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return lifecycle.get(jwt == null ? null : jwt.getSubject(), id);
    }

    /** Checkout: change snacks or voucher while the booking is PENDING and no payment has started. */
    @PutMapping("/{id}/extras")
    @PreAuthorize("isAuthenticated()")
    public BookingExtrasResponse updateExtras(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                              @RequestBody BookingExtrasRequest request) {
        return extrasService.update(jwt == null ? null : jwt.getSubject(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        lifecycle.cancel(jwt == null ? null : jwt.getSubject(), id);
    }

    /** D-15: cancel a PAID booking until 60 minutes before the show; the refund is then handled by an admin. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public CancelPaidBookingResponse cancelPaid(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return paidCancellation.cancel(jwt == null ? null : jwt.getSubject(), id);
    }
}
