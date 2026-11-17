package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.booking.NotificationResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** S14: the customer's own notifications. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notifications;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return notifications.list(jwt.getSubject(), page, size);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("unread", notifications.unreadCount(jwt.getSubject()));
    }

    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        notifications.markRead(jwt.getSubject(), id);
    }

    @PutMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(jwt.getSubject());
    }
}
