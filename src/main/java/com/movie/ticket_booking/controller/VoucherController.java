package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.VoucherResponse;
import com.movie.ticket_booking.service.PromotionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The customer's voucher wallet: promotions they saved ("Luu ma"). */
@RestController
@RequestMapping({"/api/vouchers", "/api/v1/vouchers"})
@RequiredArgsConstructor
public class VoucherController {
    private final PromotionService promotionService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<VoucherResponse> wallet(@AuthenticationPrincipal Jwt jwt) {
        return promotionService.wallet(jwt == null ? null : jwt.getSubject());
    }

    @PutMapping("/{promotionId}")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponse save(@AuthenticationPrincipal Jwt jwt, @PathVariable Long promotionId) {
        return promotionService.save(jwt == null ? null : jwt.getSubject(), promotionId);
    }

    @DeleteMapping("/{promotionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable Long promotionId) {
        promotionService.remove(jwt == null ? null : jwt.getSubject(), promotionId);
    }
}
