package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.dto.booking.TicketResponse;
import com.movie.ticket_booking.service.ticket.TicketQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketV1Controller {
    private final TicketQueryService tickets;

    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<TicketResponse> my(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return tickets.myTickets(jwt == null ? null : jwt.getSubject(), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public TicketResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return tickets.get(jwt == null ? null : jwt.getSubject(), id);
    }
}
