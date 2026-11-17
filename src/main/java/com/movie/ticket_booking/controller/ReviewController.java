package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.ReviewRequest;
import com.movie.ticket_booking.dto.ReviewResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping({"/api/movies", "/api/v1/movies"})
@RequiredArgsConstructor
public class ReviewController {
    private final ReviewService reviewService;

    /** Public; a signed-in caller's own review is flagged {@code mine}. */
    @GetMapping("/{movieId}/reviews")
    @PreAuthorize("permitAll()")
    public PageResponse<ReviewResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID movieId,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reviewService.list(movieId, jwt == null ? null : jwt.getSubject(), page, size);
    }

    @GetMapping("/{movieId}/review")
    @PreAuthorize("isAuthenticated()")
    public ReviewResponse mine(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID movieId) {
        return reviewService.mine(jwt == null ? null : jwt.getSubject(), movieId);
    }

    @PutMapping("/{movieId}/review")
    @PreAuthorize("isAuthenticated()")
    public ReviewResponse save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID movieId, @Valid @RequestBody ReviewRequest request) {
        return reviewService.save(jwt == null ? null : jwt.getSubject(), movieId, request);
    }

    @DeleteMapping("/{movieId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID movieId) {
        reviewService.delete(jwt == null ? null : jwt.getSubject(), movieId);
    }
}
