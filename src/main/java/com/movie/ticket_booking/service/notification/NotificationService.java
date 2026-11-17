package com.movie.ticket_booking.service.notification;

import com.movie.ticket_booking.dto.booking.NotificationResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.entity.enums.NotificationType;

public interface NotificationService {
    /** Idempotent per (user, type, booking); the push hook fires only for a new row. Returns whether one was created. */
    boolean notify(Long userId, NotificationType type, Long bookingId, Object... args);

    PageResponse<NotificationResponse> list(String requesterEmail, int page, int size);

    long unreadCount(String requesterEmail);

    /** 404 when the notification is not the caller's. */
    void markRead(String requesterEmail, Long id);

    int markAllRead(String requesterEmail);
}
