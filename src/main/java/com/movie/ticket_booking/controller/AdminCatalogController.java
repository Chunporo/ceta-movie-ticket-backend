package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.CinemaResponse;
import com.movie.ticket_booking.dto.ComboResponse;
import com.movie.ticket_booking.dto.PromotionResponse;
import com.movie.ticket_booking.service.CinemaService;
import com.movie.ticket_booking.service.ComboService;
import com.movie.ticket_booking.service.PromotionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The public lists hide what is switched off; the admin screens need to see (and re-enable) it. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminCatalogController {
    private final CinemaService cinemaService;
    private final ComboService comboService;
    private final PromotionService promotionService;

    @GetMapping("/cinemas")
    @PreAuthorize("hasRole('ADMIN')")
    public List<CinemaResponse> cinemas() {
        return cinemaService.listAll();
    }

    @GetMapping("/combos")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ComboResponse> combos() {
        return comboService.listAll();
    }

    @GetMapping("/promotions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PromotionResponse> promotions() {
        return promotionService.listAll();
    }
}
