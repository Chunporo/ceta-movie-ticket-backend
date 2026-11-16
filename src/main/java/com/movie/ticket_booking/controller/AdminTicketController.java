package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.booking.CheckInRequest;
import com.movie.ticket_booking.dto.booking.CheckInResponse;
import com.movie.ticket_booking.service.ticket.CheckInService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Staff use the web admin (D-19), so check-in is an ADMIN-role operation. */
@RestController
@RequestMapping("/api/v1/admin/tickets")
@RequiredArgsConstructor
public class AdminTicketController {
    private final CheckInService checkIn;

    @PostMapping("/check-in")
    @PreAuthorize("hasRole('ADMIN')")
    public CheckInResponse checkIn(@AuthenticationPrincipal Jwt jwt, @RequestBody CheckInRequest request) {
        return checkIn.checkIn(jwt == null ? null : jwt.getSubject(), request);
    }
}
