package com.movie.ticket_booking.service.notification;

import com.movie.ticket_booking.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/** One reminder per paid booking once its show is within {@code app.notification.reminder-before}. */
@Component
@Slf4j
@RequiredArgsConstructor
public class ShowtimeReminderJob {
    private final NotificationRepository notifications;

    @Value("${app.notification.reminder-before:2h}")
    private Duration before;

    @Scheduled(fixedDelayString = "${app.notification.reminder-interval:5m}", initialDelayString = "${app.notification.reminder-interval:5m}")
    public void run() {
        try {
            remind(Instant.now());
        } catch (DataIntegrityViolationException | CannotAcquireLockException e) {
            log.debug("Reminder run lost a race with another run; the next tick covers anything left", e);
        }
    }

    @Transactional
    public int remind(Instant now) {
        int created = notifications.insertReminders("Sắp đến giờ chiếu", NotificationTexts.reminderPrefix(),
                NotificationTexts.reminderSuffix(), now, now.plus(before));
        if (created > 0) {
            log.info("Showtime reminders created={}", created);
        }
        return created;
    }
}
