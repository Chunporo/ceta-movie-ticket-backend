package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CancelShowtimeResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.inventory.InventoryService;
import com.movie.ticket_booking.service.inventory.InventoryServiceImpl;
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

/** D-22: cancelling a showtime refunds PAID bookings, drops PENDING ones, and closes the showtime to new claims. */
@DataJpaTest(showSql = false)
@Import({ShowtimeCancellationServiceImpl.class, RefundServiceImpl.class, InventoryServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShowtimeCancellationServiceTest {
    @Autowired private ShowtimeCancellationService service;
    @Autowired private InventoryService inventory;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private long paidId;
    private long pendingId;
    private long seatPaid;
    private long seatPending;
    private long seatFree;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            User user = em.persist(User.builder().username(suffix).email(suffix + "@example.invalid").passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Cancel test").totalRows(1).totalColumns(3).build());
            seatPaid = em.persist(Seat.builder().room(room).label("A1").rowNumber(1).columnNumber(1).build()).getId();
            seatPending = em.persist(Seat.builder().room(room).label("A2").rowNumber(1).columnNumber(2).build()).getId();
            seatFree = em.persist(Seat.builder().room(room).label("A3").rowNumber(1).columnNumber(3).build()).getId();
            Movie movie = em.persist(Movie.builder().title("Cancel test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Booking paid = em.persist(Booking.builder().user(user).showtime(showtime).status(BookingStatus.PAID)
                    .totalAmount(100000L).holdExpiresAt(Instant.now().plusSeconds(600)).build());
            Booking pending = em.persist(Booking.builder().user(user).showtime(showtime).status(BookingStatus.PENDING)
                    .totalAmount(100000L).holdExpiresAt(Instant.now().plusSeconds(600)).build());
            em.flush();
            userId = user.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
            paidId = paid.getId();
            pendingId = pending.getId();
        });
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            inventory.initializeShowtime(showtimeId);
            Instant exp = Instant.now().plusSeconds(600);
            inventory.claim(showtimeId, List.of(seatPaid), paidId, exp);
            inventory.promoteToSold(paidId);
            inventory.claim(showtimeId, List.of(seatPending), pendingId, exp);
        });
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (?,?)", paidId, pendingId);
        jdbc.update("DELETE FROM refund_queue WHERE booking_id IN (?,?)", paidId, pendingId);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
        });
    }

    private String bookingStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, id);
    }

    @Test
    void paidIsRefundedPendingIsReleasedAndTheReasonIsKept() {
        CancelShowtimeResponse out = service.cancel(showtimeId, "Projector broke", "admin@b.c");

        assertEquals(1, out.refunded());
        assertEquals(1, out.released());
        assertEquals("REFUNDED", bookingStatus(paidId));
        assertEquals("CANCELLED", bookingStatus(pendingId));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM showtime WHERE id=?", String.class, showtimeId));
        assertEquals("Projector broke", jdbc.queryForObject("SELECT cancel_reason FROM showtime WHERE id=?", String.class, showtimeId));
        assertEquals("SHOWTIME_CANCELLED", jdbc.queryForObject("SELECT reason FROM refund_queue WHERE booking_id=?", String.class, paidId));
        assertEquals(100000L, jdbc.queryForObject("SELECT amount FROM refund_queue WHERE booking_id=?", Long.class, paidId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, pendingId));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM showtime_seat WHERE showtime_id=? AND seat_id=?", String.class, showtimeId, seatPending));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM booking_status_history WHERE reason='SHOWTIME_CANCELLED' AND booking_id IN (?,?)", Integer.class, paidId, pendingId));
    }

    @Test
    void cancellingAgainChangesNothingAndKeepsTheFirstReason() {
        service.cancel(showtimeId, "first", "admin@b.c");
        CancelShowtimeResponse again = service.cancel(showtimeId, "second", "admin@b.c");

        assertEquals(0, again.refunded());
        assertEquals(0, again.released());
        assertEquals("first", jdbc.queryForObject("SELECT cancel_reason FROM showtime WHERE id=?", String.class, showtimeId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, paidId));
    }

    @Test
    void resumesBookingsLeftOverByAnInterruptedCancel() {
        // Showtime already flipped, but the PAID booking was never reached.
        jdbc.update("UPDATE showtime SET status='CANCELLED', cancel_reason='crashed' WHERE id=?", showtimeId);

        CancelShowtimeResponse out = service.cancel(showtimeId, "retry", "admin@b.c");

        assertEquals(1, out.refunded());
        assertEquals("REFUNDED", bookingStatus(paidId));
        assertEquals("crashed", jdbc.queryForObject("SELECT cancel_reason FROM showtime WHERE id=?", String.class, showtimeId));
    }

    @Test
    void aCancelledShowtimeRefusesNewClaims() {
        service.cancel(showtimeId, "closed", "admin@b.c");

        BusinessException ex = assertThrows(BusinessException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(
                s -> inventory.claim(showtimeId, List.of(seatFree), pendingId, Instant.now().plusSeconds(600))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("SHOWTIME_CANCELLED", ex.getCode());
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM showtime_seat WHERE showtime_id=? AND seat_id=?", String.class, showtimeId, seatFree));
    }

    @Test
    void reasonIsMandatoryAndUnknownShowtimeIs404() {
        assertEquals("REASON_REQUIRED", assertThrows(BusinessException.class, () -> service.cancel(showtimeId, "  ", "a")).getCode());
        assertEquals("REASON_REQUIRED", assertThrows(BusinessException.class, () -> service.cancel(showtimeId, null, "a")).getCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () -> service.cancel(987654321L, "x", "a")).getStatus());
        assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM showtime WHERE id=?", String.class, showtimeId));
    }
}
