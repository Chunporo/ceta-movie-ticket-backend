package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CancelPaidBookingResponse;
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

/** D-15: a customer cancels a PAID booking until 60 minutes before the show; PAID -> REFUNDED, seats back on sale. */
@DataJpaTest(showSql = false)
@Import({PaidBookingCancellationServiceImpl.class, RefundServiceImpl.class, InventoryServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaidBookingCancellationServiceTest {
    @Autowired private PaidBookingCancellationService service;
    @Autowired private InventoryService inventory;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String ownerEmail;
    private String strangerEmail;
    private long ownerId;
    private long strangerId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private long bookingId;
    private long ticketId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        seed(Instant.now().plus(1, ChronoUnit.DAYS), BookingStatus.PAID);
    }

    private void seed(Instant start, BookingStatus status) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            String suffix = UUID.randomUUID().toString();
            ownerEmail = "o" + suffix + "@example.invalid";
            strangerEmail = "s" + suffix + "@example.invalid";
            ownerId = em.persist(User.builder().username(("o" + suffix).substring(0, 30)).email(ownerEmail).passwordHash("x").build()).getId();
            strangerId = em.persist(User.builder().username(("s" + suffix).substring(0, 30)).email(strangerEmail).passwordHash("x").build()).getId();
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Paid cancel").totalRows(1).totalColumns(2).build());
            roomId = room.getId();
            Seat s1 = em.persist(Seat.builder().room(room).label("A1").rowNumber(1).columnNumber(1).build());
            Seat s2 = em.persist(Seat.builder().room(room).label("A2").rowNumber(1).columnNumber(2).build());
            seatIds = List.of(s1.getId(), s2.getId());
            Movie movie = em.persist(Movie.builder().title("Paid cancel").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            movieId = movie.getId();
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start.truncatedTo(ChronoUnit.SECONDS))
                    .endTime(start.plusSeconds(7200).truncatedTo(ChronoUnit.SECONDS)).basePrice(BigDecimal.valueOf(100000)).build());
            showtimeId = showtime.getId();
            Booking booking = em.persist(Booking.builder().user(em.find(User.class, ownerId)).showtime(showtime).status(status)
                    .totalAmount(200000L).holdExpiresAt(Instant.now().minusSeconds(60)).build());
            bookingId = booking.getId();
            for (Seat seat : List.of(s1, s2)) {
                Ticket t = em.persist(Ticket.builder().booking(booking).showtime(showtime).seat(seat)
                        .price(BigDecimal.valueOf(100000)).code("TCK-" + UUID.randomUUID()).build());
                if (seat == s1) ticketId = t.getId();
            }
            em.flush();
        });
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            inventory.initializeShowtime(showtimeId);
            if (status == BookingStatus.PAID) {
                inventory.claim(showtimeId, seatIds, bookingId, Instant.now().plusSeconds(600));
                inventory.promoteToSold(bookingId);
            }
        });
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id=?", bookingId);
        jdbc.update("DELETE FROM refund_queue WHERE booking_id=?", bookingId);
        jdbc.update("DELETE FROM tickets WHERE booking_id=?", bookingId);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE id=?", bookingId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, ownerId));
            em.remove(em.find(User.class, strangerId));
        });
    }

    private String bookingStatus() {
        return jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId);
    }

    private void reseed(Instant start, BookingStatus status) {
        clean();
        seed(start, status);
    }

    @Test
    void ownerCancelsRefundIsQueuedAndSeatsGoBackOnSale() {
        CancelPaidBookingResponse out = service.cancel(ownerEmail, bookingId);

        assertEquals("REFUNDED", out.status());
        assertEquals(200000L, out.refundAmount());
        assertEquals("REFUNDED", bookingStatus());
        assertEquals("CUSTOMER_CANCEL", jdbc.queryForObject("SELECT reason FROM refund_queue WHERE booking_id=?", String.class, bookingId));
        assertEquals(200000L, jdbc.queryForObject("SELECT amount FROM refund_queue WHERE booking_id=?", Long.class, bookingId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM refund_queue WHERE booking_id=?", String.class, bookingId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='AVAILABLE'", Integer.class, showtimeId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_status_history WHERE booking_id=? AND reason='CUSTOMER_CANCEL'", Integer.class, bookingId));
    }

    @Test
    void cancellingTwiceIsRefusedAndRefundsOnce() {
        service.cancel(ownerEmail, bookingId);

        BusinessException again = assertThrows(BusinessException.class, () -> service.cancel(ownerEmail, bookingId));
        assertEquals("BOOKING_NOT_CANCELLABLE", again.getCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, bookingId));
    }

    @Test
    void someoneElsesBookingIs403AndNothingChanges() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(strangerEmail, bookingId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("PAID", bookingStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, bookingId));
    }

    @Test
    void lessThanSixtyMinutesBeforeTheShowIsLocked() {
        reseed(Instant.now().plus(59, ChronoUnit.MINUTES), BookingStatus.PAID);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(ownerEmail, bookingId));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("BOOKING_NOT_CANCELLABLE", ex.getCode());
        assertEquals("PAID", bookingStatus());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='SOLD'", Integer.class, showtimeId));
    }

    @Test
    void justOverSixtyMinutesIsStillAllowed() {
        reseed(Instant.now().plus(61, ChronoUnit.MINUTES), BookingStatus.PAID);
        assertEquals("REFUNDED", service.cancel(ownerEmail, bookingId).status());
    }

    @Test
    void aUsedTicketBlocksTheCancellation() {
        jdbc.update("UPDATE tickets SET checked_in_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now()), ticketId);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(ownerEmail, bookingId));
        assertEquals("TICKET_ALREADY_USED", ex.getCode());
        assertEquals("PAID", bookingStatus());
    }

    @Test
    void pendingAndUnknownBookingsAreRefused() {
        reseed(Instant.now().plus(1, ChronoUnit.DAYS), BookingStatus.PENDING);
        assertEquals("BOOKING_NOT_CANCELLABLE", assertThrows(BusinessException.class, () -> service.cancel(ownerEmail, bookingId)).getCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () -> service.cancel(ownerEmail, 987654321L)).getStatus());
    }
}
