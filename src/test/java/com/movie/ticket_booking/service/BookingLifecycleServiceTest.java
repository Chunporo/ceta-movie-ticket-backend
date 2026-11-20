package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.BookingDetailResponse;
import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.BusinessException;
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

@DataJpaTest(showSql = false)
@Import({BookingServiceImpl.class, BookingLifecycleServiceImpl.class, BookingCreationServiceImpl.class, com.movie.ticket_booking.service.BookingExtrasCalculator.class, InventoryServiceImpl.class, PricingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingLifecycleServiceTest {
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private BookingCreationService creation;
    @Autowired private BookingService bookingService;
    @Autowired private com.movie.ticket_booking.repository.RefundQueueRepository refundQueue;
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
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            ownerEmail = "o" + suffix + "@example.invalid";
            strangerEmail = "s" + suffix + "@example.invalid";
            User owner = em.persist(User.builder().username("o" + suffix).email(ownerEmail).passwordHash("x").build());
            User stranger = em.persist(User.builder().username("s" + suffix).email(strangerEmail).passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Lifecycle test").totalRows(1).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Lifecycle test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            seatIds = List.of(1, 2).stream().map(col -> {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).build());
                em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId())).showtime(showtime).seat(seat).build());
                return seat.getId();
            }).toList();
            em.flush();
            ownerId = owner.getId();
            strangerId = stranger.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
    }

    @AfterEach
    void clean() {
        String bookingIds = "SELECT id FROM booking WHERE showtime_id=" + showtimeId;
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, ownerId));
            em.remove(em.find(User.class, strangerId));
        });
    }

    private long book() {
        CreateBookingResponse r = creation.create(ownerEmail, new CreateBookingRequest(showtimeId, seatIds));
        return r.bookingId();
    }

    @Test
    void detailCarriesWhatTheConfirmationScreenNeeds() {
        long id = book();

        var detail = lifecycle.get(ownerEmail, id);

        assertEquals(id, detail.id());
        assertEquals("Lifecycle test", detail.movieTitle());
        assertEquals("Lifecycle test", detail.roomName());
        assertNotNull(detail.startTime());
        assertFalse(detail.refundRequired());
    }

    @Test
    void refundRequiredWhileARefundIsOwedAndClearsOnceItIsPaid() {
        long id = book();
        jdbc.update("UPDATE booking SET status='EXPIRED' WHERE id=?", id);
        jdbc.update("INSERT INTO refund_queue (booking_id, reason, amount, status, created_at) VALUES (?, 'LATE_PAYMENT', 100000, 'PENDING', CURRENT_TIMESTAMP)", id);
        assertTrue(lifecycle.get(ownerEmail, id).refundRequired());

        jdbc.update("UPDATE refund_queue SET status='DONE' WHERE booking_id=?", id);
        assertFalse(lifecycle.get(ownerEmail, id).refundRequired());
        jdbc.update("DELETE FROM refund_queue WHERE booking_id=?", id);
    }

    @Test
    void historyIsPagedNewestFirstAndShowsSeatsOfAPendingBooking() {
        long first = book();
        lifecycle.cancel(ownerEmail, first);
        long second = book();

        var page = bookingService.getUserBookings(ownerEmail, 0, 1);

        assertEquals(1, page.items().size());
        assertEquals(2, page.totalItems());
        assertEquals(2, page.totalPages());
        assertEquals(second, page.items().get(0).getId());
        assertEquals(List.of("A1", "A2"), page.items().get(0).getSeatNames());
        assertEquals(first, bookingService.getUserBookings(ownerEmail, 1, 1).items().get(0).getId());
        assertEquals(0, bookingService.getUserBookings(ownerEmail, 7, 1).items().size());
        assertEquals(0, bookingService.getUserBookings(strangerEmail, 0, 10).items().size());
    }

    @Test
    void cancelReleasesSeatsCancelsBookingAndWritesHistory() {
        long id = book();

        lifecycle.cancel(ownerEmail, id);

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND (status<>'AVAILABLE' OR booking_id IS NOT NULL OR held_until IS NOT NULL)", Integer.class, showtimeId));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_status_history WHERE booking_id=? AND from_status='PENDING' AND to_status='CANCELLED'", Integer.class, id));
    }

    @Test
    void cancellingTwiceGives409NotAServerError() {
        long id = book();
        lifecycle.cancel(ownerEmail, id);

        BusinessException ex = assertThrows(BusinessException.class, () -> lifecycle.cancel(ownerEmail, id));

        assertEquals("BOOKING_NOT_CANCELLABLE", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void nonOwnerGets403AndSeatsStayHeld() {
        long id = book();

        BusinessException ex = assertThrows(BusinessException.class, () -> lifecycle.cancel(strangerEmail, id));
        BusinessException read = assertThrows(BusinessException.class, () -> lifecycle.get(strangerEmail, id));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals(HttpStatus.FORBIDDEN, read.getStatus());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='HELD'", Integer.class, showtimeId));
    }

    @Test
    void paidBookingCannotBeCancelled() {
        long id = book();
        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", id);

        BusinessException ex = assertThrows(BusinessException.class, () -> lifecycle.cancel(ownerEmail, id));

        assertEquals("BOOKING_NOT_CANCELLABLE", ex.getCode());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='HELD'", Integer.class, showtimeId));
    }

    @Test
    void unknownBookingIs404() {
        BusinessException ex = assertThrows(BusinessException.class, () -> lifecycle.get(ownerEmail, 987654321L));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void secondsRemainingCountsDownOnTheServer() throws Exception {
        long id = book();
        BookingDetailResponse first = lifecycle.get(ownerEmail, id);
        Thread.sleep(2100);
        BookingDetailResponse second = lifecycle.get(ownerEmail, id);

        long drop = first.secondsRemaining() - second.secondsRemaining();
        assertTrue(drop == 2 || drop == 3, "expected ~2s drop, was " + drop);
        assertTrue(first.secondsRemaining() <= 600 && first.secondsRemaining() >= 598);
        assertEquals(BookingStatus.PENDING, first.status());
        assertNotNull(first.showtimeId());
        assertEquals(2, first.seats().size());
        assertEquals(java.util.Set.of("A1", "A2"), first.seats().stream().map(BookingDetailResponse.Seat::label).collect(java.util.stream.Collectors.toSet()));
        assertTrue(first.tickets().isEmpty());
    }

    @Test
    void secondsRemainingIsZeroOnceThePendingHoldHasLapsed() {
        long id = book();
        jdbc.update("UPDATE booking SET hold_expires_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(30)), id);

        assertEquals(0, lifecycle.get(ownerEmail, id).secondsRemaining());
    }
}
