package com.movie.ticket_booking.service.notification;

import com.movie.ticket_booking.dto.booking.NotificationResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.event.*;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.listener.NotificationListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** S14: the notification store, the order-event listener and the showtime reminder job (H2). */
@DataJpaTest(showSql = false)
@Import({NotificationServiceImpl.class, NotificationWriter.class, NotificationListener.class, ShowtimeReminderJob.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationFlowTest {
    @Autowired private NotificationService service;
    @Autowired private NotificationListener listener;
    @Autowired private ShowtimeReminderJob reminders;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private PushSender push;

    private String ownerEmail;
    private String strangerEmail;
    private long ownerId;
    private long strangerId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private long bookingId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            String suffix = UUID.randomUUID().toString();
            ownerEmail = "o" + suffix + "@example.invalid";
            strangerEmail = "s" + suffix + "@example.invalid";
            User owner = em.persist(User.builder().username(("o" + suffix).substring(0, 30)).email(ownerEmail).passwordHash("x").build());
            User stranger = em.persist(User.builder().username(("s" + suffix).substring(0, 30)).email(strangerEmail).passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Notify test").totalRows(1).totalColumns(1).build());
            Movie movie = em.persist(Movie.builder().title("Notify test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Booking booking = em.persist(Booking.builder().user(owner).showtime(showtime).status(BookingStatus.PAID)
                    .totalAmount(100000L).holdExpiresAt(Instant.now().minusSeconds(60)).build());
            em.flush();
            ownerId = owner.getId();
            strangerId = stranger.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
            bookingId = booking.getId();
        });
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM notification WHERE user_id IN (?,?)", ownerId, strangerId);
        jdbc.update("DELETE FROM booking WHERE id=?", bookingId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, ownerId));
            em.remove(em.find(User.class, strangerId));
        });
    }

    private int count(NotificationType type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND type=?", Integer.class, ownerId, type.name());
    }

    // ---- the store

    @Test
    void notifyIsIdempotentPerUserTypeAndBookingAndPushesOnlyOnce() {
        assertTrue(service.notify(ownerId, NotificationType.ORDER_PAID, bookingId));
        assertFalse(service.notify(ownerId, NotificationType.ORDER_PAID, bookingId));
        assertTrue(service.notify(ownerId, NotificationType.REFUND_DONE, bookingId)); // another type is its own row

        assertEquals(1, count(NotificationType.ORDER_PAID));
        verify(push, times(2)).send(eq(ownerId), anyString(), anyString());
    }

    @Test
    void aFailingPushNeverLosesTheInAppNotification() {
        doThrow(new IllegalStateException("fcm down")).when(push).send(any(), any(), any());

        assertTrue(service.notify(ownerId, NotificationType.ORDER_PAID, bookingId));
        assertEquals(1, count(NotificationType.ORDER_PAID));
    }

    @Test
    void listIsNewestFirstPagedAndOwnOnly() {
        service.notify(ownerId, NotificationType.ORDER_PAID, bookingId);
        service.notify(ownerId, NotificationType.REFUND_PENDING, bookingId);
        service.notify(ownerId, NotificationType.REFUND_DONE, bookingId);

        PageResponse<NotificationResponse> first = service.list(ownerEmail, 0, 2);

        assertEquals(3, first.totalItems());
        assertEquals(2, first.totalPages());
        assertEquals(2, first.items().size());
        assertEquals("REFUND_DONE", first.items().get(0).type());
        assertEquals("REFUND_PENDING", first.items().get(1).type());
        assertEquals("ORDER_PAID", service.list(ownerEmail, 1, 2).items().get(0).type());
        assertEquals(0, service.list(strangerEmail, 0, 20).items().size());
        assertTrue(first.items().get(0).title().length() > 0);
    }

    @Test
    void readStateAndUnreadCount() {
        service.notify(ownerId, NotificationType.ORDER_PAID, bookingId);
        service.notify(ownerId, NotificationType.REFUND_PENDING, bookingId);
        assertEquals(2, service.unreadCount(ownerEmail));

        long id = service.list(ownerEmail, 0, 1).items().get(0).id();
        service.markRead(ownerEmail, id);
        service.markRead(ownerEmail, id); // again: still fine, keeps the first read time
        assertEquals(1, service.unreadCount(ownerEmail));
        assertTrue(service.list(ownerEmail, 0, 1).items().get(0).read());

        assertEquals(1, service.markAllRead(ownerEmail));
        assertEquals(0, service.unreadCount(ownerEmail));
    }

    @Test
    void someoneElsesNotificationIs404AndStaysUnread() {
        service.notify(ownerId, NotificationType.ORDER_PAID, bookingId);
        long id = service.list(ownerEmail, 0, 1).items().get(0).id();

        BusinessException ex = assertThrows(BusinessException.class, () -> service.markRead(strangerEmail, id));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals(1, service.unreadCount(ownerEmail));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () -> service.markRead(ownerEmail, 987654321L)).getStatus());
    }

    // ---- the listener

    @Test
    void orderEventsBecomeNotificationsAndRepeatsAreHarmless() {
        listener.onPaid(new BookingCheckedOutEvent(bookingId));
        listener.onPaid(new BookingCheckedOutEvent(bookingId));
        listener.onRefundQueued(new RefundQueuedEvent(bookingId, RefundReason.LATE_PAYMENT));
        listener.onRefundCompleted(new RefundCompletedEvent(bookingId));

        assertEquals(1, count(NotificationType.ORDER_PAID));
        assertEquals(1, count(NotificationType.REFUND_PENDING));
        assertEquals(1, count(NotificationType.REFUND_DONE));
    }

    @Test
    void aCancelledShowtimeGetsItsOwnNotificationNotTheGenericRefundOne() {
        listener.onRefundQueued(new RefundQueuedEvent(bookingId, RefundReason.SHOWTIME_CANCELLED));
        listener.onShowtimeCancelled(new ShowtimeCancelledRefundEvent(bookingId, "Hỏng máy chiếu"));

        assertEquals(0, count(NotificationType.REFUND_PENDING));
        assertEquals(1, count(NotificationType.SHOWTIME_CANCELLED));
        assertTrue(service.list(ownerEmail, 0, 1).items().get(0).body().contains("Hỏng máy chiếu"));
    }

    @Test
    void anUnknownBookingIsIgnored() {
        assertDoesNotThrow(() -> listener.onPaid(new BookingCheckedOutEvent(987654321L)));
    }

    // ---- the reminder job

    @Test
    void reminderIsSentOnceForAPaidBookingInsideTheWindow() {
        assertEquals(1, reminders.remind(Instant.now()));
        assertEquals(0, reminders.remind(Instant.now()));

        assertEquals(1, count(NotificationType.SHOWTIME_REMINDER));
        assertTrue(service.list(ownerEmail, 0, 1).items().get(0).body().contains("#" + bookingId));
    }

    @Test
    void noReminderOutsideTheWindowOrForUnpaidOrCancelledShows() {
        jdbc.update("UPDATE showtime SET start_time = ? WHERE id=?", java.sql.Timestamp.from(Instant.now().plus(5, ChronoUnit.HOURS)), showtimeId);
        assertEquals(0, reminders.remind(Instant.now()));

        jdbc.update("UPDATE showtime SET start_time = ? WHERE id=?", java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), showtimeId);
        jdbc.update("UPDATE booking SET status='PENDING' WHERE id=?", bookingId);
        assertEquals(0, reminders.remind(Instant.now()));

        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", bookingId);
        jdbc.update("UPDATE showtime SET status='CANCELLED' WHERE id=?", showtimeId);
        assertEquals(0, reminders.remind(Instant.now()));

        jdbc.update("UPDATE showtime SET status='SCHEDULED', start_time = ? WHERE id=?", java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), showtimeId);
        assertEquals(0, reminders.remind(Instant.now()), "a show that already started needs no reminder");
    }
}
