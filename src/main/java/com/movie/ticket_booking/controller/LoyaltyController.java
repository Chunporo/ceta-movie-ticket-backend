package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.LoyaltyAdjustRequest;
import com.movie.ticket_booking.dto.LoyaltySummaryResponse;
import com.movie.ticket_booking.service.LoyaltyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class LoyaltyController {
    private final LoyaltyService loyaltyService;

    /** CETA points, tier and the account counters of the signed-in customer. */
    @GetMapping({"/api/v1/me/loyalty", "/api/me/loyalty"})
    @PreAuthorize("isAuthenticated()")
    public LoyaltySummaryResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return loyaltyService.summary(jwt == null ? null : jwt.getSubject());
    }

    @PostMapping("/api/v1/admin/loyalty/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    public LoyaltySummaryResponse adjust(@Valid @RequestBody LoyaltyAdjustRequest request) {
        return loyaltyService.adjust(request);
    }
}
