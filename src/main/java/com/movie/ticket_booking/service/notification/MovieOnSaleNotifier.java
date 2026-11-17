package com.movie.ticket_booking.service.notification;

import com.movie.ticket_booking.entity.MovieReminder;
import com.movie.ticket_booking.entity.Notification;
import com.movie.ticket_booking.entity.enums.NotificationType;
import com.movie.ticket_booking.event.MovieOnSaleEvent;
import com.movie.ticket_booking.repository.MovieReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Tells everyone waiting for a movie that tickets are on sale, once, after the showtime has committed. A failure here
 * can never undo the showtime; the reminders are cleared only after every notification was written.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MovieOnSaleNotifier {
    private final MovieReminderRepository reminders;
    private final NotificationWriter writer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSale(MovieOnSaleEvent event) {
        try {
            String[] text = NotificationTexts.of(NotificationType.MOVIE_ON_SALE, null, event.movieTitle());
            int told = 0;
            for (MovieReminder reminder : reminders.findByIdMovieId(event.movieId())) {
                writer.insert(Notification.builder().userId(reminder.getId().getUserId()).type(NotificationType.MOVIE_ON_SALE)
                        .title(text[0]).body(text[1]).movieId(event.movieId()).createdAt(Instant.now()).build());
                told++;
            }
            reminders.deleteAllForMovie(event.movieId());
            if (told > 0) {
                log.info("Movie on sale movieId={} notified={}", event.movieId(), told);
            }
        } catch (RuntimeException e) {
            log.warn("Could not notify reminders for movieId={}", event.movieId(), e);
        }
    }
}
