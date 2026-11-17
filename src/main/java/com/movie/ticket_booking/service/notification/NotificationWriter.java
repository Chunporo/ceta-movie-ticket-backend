package com.movie.ticket_booking.service.notification;

import com.movie.ticket_booking.entity.Notification;
import com.movie.ticket_booking.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One row, its own transaction. A duplicate (unique key) or a lock conflict then only rolls this insert back,
 * so the caller can treat it as "already notified" without poisoning its own transaction.
 */
@Component
@RequiredArgsConstructor
public class NotificationWriter {
    private final NotificationRepository notifications;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Notification notification) {
        notifications.saveAndFlush(notification);
    }
}
