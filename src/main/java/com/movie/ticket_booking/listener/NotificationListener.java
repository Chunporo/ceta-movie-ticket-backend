package com.movie.ticket_booking.listener;

import com.movie.ticket_booking.entity.enums.NotificationType;
import com.movie.ticket_booking.entity.enums.RefundReason;
import com.movie.ticket_booking.event.*;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns committed order events into in-app notifications. Runs after commit in its own transaction, so a
 * problem here can never undo a payment or a refund; the unique key makes a repeat harmless.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationListener {
    private final BookingRepository bookings;
    private final NotificationService notifications;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaid(BookingCheckedOutEvent event) {
        notify(event.bookingId(), NotificationType.ORDER_PAID);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRefundQueued(RefundQueuedEvent event) {
        // A cancelled showtime tells the customer through its own, more specific notification.
        if (event.reason() != RefundReason.SHOWTIME_CANCELLED) {
            notify(event.bookingId(), NotificationType.REFUND_PENDING);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRefundCompleted(RefundCompletedEvent event) {
        notify(event.bookingId(), NotificationType.REFUND_DONE);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onShowtimeCancelled(ShowtimeCancelledRefundEvent event) {
        notify(event.bookingId(), NotificationType.SHOWTIME_CANCELLED, event.reason());
    }

    private void notify(Long bookingId, NotificationType type, Object... args) {
        try {
            bookings.findById(bookingId).ifPresent(b -> notifications.notify(b.getUser().getId(), type, bookingId, args));
        } catch (RuntimeException e) {
            log.warn("Notification failed bookingId={} type={}", bookingId, type, e);
        }
    }
}
