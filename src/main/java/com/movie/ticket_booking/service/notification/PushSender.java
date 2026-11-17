package com.movie.ticket_booking.service.notification;

/**
 * Seam for a push provider (FCM). The v1 app must work without push (Android spec 6.2), so the default
 * implementation only logs; a real one needs a provider dependency and device-token storage.
 */
public interface PushSender {
    void send(Long userId, String title, String body);
}
