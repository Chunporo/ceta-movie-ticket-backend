package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.RefundResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import(RefundServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundServiceTest {
    @Autowired private RefundService service;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private long bookingId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            User user = em.persist(User.builder().username(suffix).email(suffix + "@example.invalid").passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Refund test").totalRows(1).totalColumns(1).build());
            Movie movie = em.persist(Movie.builder().title("Refund test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Booking booking = em.persist(Booking.builder().user(user).showtime(showtime).status(BookingStatus.EXPIRED)
                    .totalAmount(100000L).holdExpiresAt(Instant.now().minusSeconds(60)).build());
            em.flush();
            userId = user.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
            bookingId = booking.getId();
        });
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id=?", bookingId);
        jdbc.update("DELETE FROM refund_queue WHERE booking_id=?", bookingId);
        jdbc.update("DELETE FROM booking WHERE id=?", bookingId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
        });
    }

    @Test
    void enqueueTwiceKeepsOneRow() {
        service.enqueue(bookingId, RefundReason.LATE_PAYMENT, 100000L, "first");
        service.enqueue(bookingId, RefundReason.DOUBLE_PAY, 100000L, "second");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, bookingId));
        assertEquals("LATE_PAYMENT", jdbc.queryForObject("SELECT reason FROM refund_queue WHERE booking_id=?", String.class, bookingId));
        List<RefundResponse> pending = service.list(RefundStatus.PENDING);
        assertTrue(pending.stream().anyMatch(r -> r.bookingId() == bookingId && r.amount() == 100000L));
    }

    @Test
    void doneStampsTimeRefundsTheExpiredBookingAndIsFinal() {
        service.enqueue(bookingId, RefundReason.LATE_PAYMENT, 100000L, null);
        long id = service.list(RefundStatus.PENDING).stream().filter(r -> r.bookingId() == bookingId).findFirst().orElseThrow().id();

        RefundResponse done = service.markDone(id, "Refunded in VNPay portal, ref 123");

        assertEquals("DONE", done.status());
        assertNotNull(done.doneAt());
        assertEquals("Refunded in VNPay portal, ref 123", done.note());
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_status_history WHERE booking_id=? AND to_status='REFUNDED'", Integer.class, bookingId));
        BusinessException again = assertThrows(BusinessException.class, () -> service.markDone(id, null));
        assertEquals(HttpStatus.CONFLICT, again.getStatus());
        assertThrows(BusinessException.class, () -> service.markFailed(id, "too late"));
    }

    @Test
    void failedCanBeRetriedToDoneButUnknownIdIs404() {
        service.enqueue(bookingId, RefundReason.LATE_PAYMENT, 100000L, null);
        long id = service.list(RefundStatus.PENDING).stream().filter(r -> r.bookingId() == bookingId).findFirst().orElseThrow().id();

        RefundResponse failed = service.markFailed(id, "bank rejected");
        assertEquals("FAILED", failed.status());
        assertNull(failed.doneAt());
        assertEquals("DONE", service.markDone(id, "retried").status());

        BusinessException missing = assertThrows(BusinessException.class, () -> service.markDone(987654321L, null));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
    }
}
