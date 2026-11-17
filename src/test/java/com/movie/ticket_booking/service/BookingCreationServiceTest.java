package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.exception.SeatConflictException;
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

/** F2 behaviour on H2; the MySQL row-lock race is covered by ConcurrentClaimTest. */
@DataJpaTest(showSql = false)
@Import({BookingCreationServiceImpl.class, InventoryServiceImpl.class, PricingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingCreationServiceTest {
    @Autowired private BookingCreationService service;
    @Autowired private InventoryService inventory;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String email;
    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private List<Long> standard;   // A1, A2
    private List<Long> sweetbox;   // B1, B2 (a pair)

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            email = suffix + "@example.invalid";
            User user = em.persist(User.builder().username(suffix).email(email).passwordHash("test-only").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Booking test")
                    .totalRows(2).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Booking test").slug(suffix)
                    .posterUrl("https://example.invalid/poster").ageRating(AgeRating.P)
                    .status(MovieStatus.NOW_SHOWING).duration(120).build());
            Showtime showtime = em.persist(showtime(movie, room, Instant.now().plus(1, ChronoUnit.DAYS)));
            standard = List.of(seat(room, showtime, "A1", 1, 1, SeatType.STANDARD), seat(room, showtime, "A2", 1, 2, SeatType.STANDARD));
            sweetbox = List.of(seat(room, showtime, "B1", 2, 1, SeatType.SWEETBOX), seat(room, showtime, "B2", 2, 2, SeatType.SWEETBOX));
            em.flush();
            userId = user.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (SELECT id FROM booking WHERE showtime_id IN (SELECT id FROM showtime WHERE room_id=?))", roomId);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id IN (SELECT id FROM showtime WHERE room_id=?)", roomId);
        jdbc.update("DELETE FROM booking WHERE showtime_id IN (SELECT id FROM showtime WHERE room_id=?)", roomId);
        jdbc.update("DELETE FROM showtime WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
        });
    }

    @Test
    void createsPendingBookingHoldsSeatsAndSnapshotsPrices() {
        CreateBookingResponse response = service.create(email, new CreateBookingRequest(showtimeId, standard));

        assertEquals(2, response.seats().size());
        assertEquals(response.seats().stream().mapToLong(CreateBookingResponse.Seat::price).sum(), response.totalAmount());
        assertTrue(response.holdExpiresAt().isAfter(Instant.now()));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, response.bookingId()));
        assertEquals(response.totalAmount(), jdbc.queryForObject("SELECT total_amount FROM booking WHERE id=?", Long.class, response.bookingId()));
        assertEquals(response.totalAmount(), jdbc.queryForObject("SELECT SUM(price) FROM booking_seat WHERE booking_id=?", Long.class, response.bookingId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE status='HELD' AND booking_id=? AND held_until IS NOT NULL", Integer.class, response.bookingId()));
    }

    @Test
    void seatAlreadyHeldGivesConflictWithDetailsAndLeavesNoBooking() {
        Long otherBooking = new TransactionTemplate(transactions).execute(status -> {
            User other = em.persist(User.builder().username("o" + UUID.randomUUID()).email("o" + UUID.randomUUID() + "@example.invalid").passwordHash("x").build());
            Booking b = em.persist(Booking.builder().user(other).showtime(em.find(Showtime.class, showtimeId))
                    .totalAmount(1L).holdExpiresAt(Instant.now().plusSeconds(600)).build());
            em.flush();
            return b.getId();
        });
        inventory.claim(showtimeId, List.of(standard.get(1)), otherBooking, Instant.now().plusSeconds(600));

        SeatConflictException ex = assertThrows(SeatConflictException.class,
                () -> service.create(email, new CreateBookingRequest(showtimeId, standard)));

        assertEquals(List.of(standard.get(1)), ex.getConflictSeatIds());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE user_id=?", Integer.class, userId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE status='HELD' AND showtime_id=?", Integer.class, showtimeId));
    }

    @Test
    void ninthSeatIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(email,
                new CreateBookingRequest(showtimeId, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L))));
        assertEquals("MAX_SEATS_EXCEEDED", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void secondPendingBookingForSameShowtimeIsRejected() {
        service.create(email, new CreateBookingRequest(showtimeId, List.of(standard.get(0))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(email, new CreateBookingRequest(showtimeId, List.of(standard.get(1)))));

        assertEquals("BOOKING_ALREADY_PENDING", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE status='HELD' AND showtime_id=?", Integer.class, showtimeId));
    }

    @Test
    void showtimeBeyondFourteenDaysOrAlreadyStartedIsOutsideSaleWindow() {
        long far = newShowtime(Instant.now().plus(15, ChronoUnit.DAYS));
        long started = newShowtime(Instant.now().minusSeconds(60));
        for (long id : new long[]{far, started}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.create(email, new CreateBookingRequest(id, standard)));
            assertEquals("SHOWTIME_CLOSED", ex.getCode());
        }
    }

    @Test
    void halfACoupleSeatIsRejectedAndFullPairIsAccepted() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(email, new CreateBookingRequest(showtimeId, List.of(sweetbox.get(0)))));
        assertEquals("INVALID_COUPLE_SEAT", ex.getCode());

        CreateBookingResponse ok = service.create(email, new CreateBookingRequest(showtimeId, sweetbox));
        assertEquals(2, ok.seats().size());
    }

    @Test
    void fourthBookingWithinAnHourIsRefusedEvenWhenEarlierOnesWereCancelledOrExpired() {
        for (String ended : new String[]{"CANCELLED", "EXPIRED", "CANCELLED"}) {
            endBooking(service.create(email, new CreateBookingRequest(showtimeId, List.of(standard.get(0)))).bookingId(), ended);
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(email, new CreateBookingRequest(showtimeId, List.of(standard.get(0)))));

        assertEquals("TOO_MANY_ATTEMPTS", ex.getCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE user_id=?", Integer.class, userId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status<>'AVAILABLE'", Integer.class, showtimeId));
    }

    @Test
    void attemptsOlderThanAnHourDoNotCount() {
        for (int i = 0; i < 3; i++) {
            endBooking(service.create(email, new CreateBookingRequest(showtimeId, List.of(standard.get(0)))).bookingId(), "CANCELLED");
        }
        jdbc.update("UPDATE booking SET created_at=? WHERE user_id=?", java.sql.Timestamp.from(Instant.now().minus(61, ChronoUnit.MINUTES)), userId);

        assertDoesNotThrow(() -> service.create(email, new CreateBookingRequest(showtimeId, List.of(standard.get(0)))));
    }

    @Test
    void theLimitIsPerShowtimeAndPerUser() {
        for (int i = 0; i < 3; i++) {
            endBooking(service.create(email, new CreateBookingRequest(showtimeId, List.of(standard.get(0)))).bookingId(), "CANCELLED");
        }
        long otherShowtime = newShowtime(Instant.now().plus(2, ChronoUnit.DAYS));
        long otherSeat = newSeat(otherShowtime);

        assertDoesNotThrow(() -> service.create(email, new CreateBookingRequest(otherShowtime, List.of(otherSeat))),
                "a different showtime has its own allowance");

        String other = "o" + UUID.randomUUID().toString().substring(0, 20) + "@example.invalid";
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                em.persist(User.builder().username(other.substring(0, 25)).email(other).passwordHash("x").build()));
        assertDoesNotThrow(() -> service.create(other, new CreateBookingRequest(showtimeId, List.of(standard.get(0)))),
                "another user is unaffected");

        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (SELECT id FROM booking WHERE user_id IN (SELECT id FROM users WHERE email=?))", other);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=? AND booking_id IN (SELECT id FROM booking WHERE user_id IN (SELECT id FROM users WHERE email=?))", showtimeId, other);
        jdbc.update("DELETE FROM booking WHERE user_id IN (SELECT id FROM users WHERE email=?)", other);
        jdbc.update("DELETE FROM users WHERE email=?", other);
    }

    private void endBooking(long bookingId, String status) {
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE booking_id=?", bookingId);
        jdbc.update("UPDATE booking SET status=? WHERE id=?", status, bookingId);
    }

    private long newSeat(long forShowtime) {
        return new TransactionTemplate(transactions).execute(status -> {
            Showtime showtime = em.find(Showtime.class, forShowtime);
            Seat seat = em.persist(Seat.builder().room(showtime.getRoom()).label("Z9").rowNumber(9).columnNumber(9).build());
            em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(forShowtime, seat.getId())).showtime(showtime).seat(seat).build());
            em.flush();
            return seat.getId();
        });
    }

    private long newShowtime(Instant start) {
        return new TransactionTemplate(transactions).execute(status -> {
            Showtime s = em.persist(showtime(em.find(Movie.class, movieId), em.find(Room.class, roomId), start));
            em.flush();
            return s.getId();
        });
    }

    private static Showtime showtime(Movie movie, Room room, Instant start) {
        Instant truncated = start.truncatedTo(ChronoUnit.SECONDS);
        return Showtime.builder().movie(movie).room(room).startTime(truncated).endTime(truncated.plusSeconds(7200))
                .basePrice(BigDecimal.valueOf(100000)).build();
    }

    private long seat(Room room, Showtime showtime, String label, int row, int col, SeatType type) {
        Seat seat = em.persist(Seat.builder().room(room).label(label).rowNumber(row).columnNumber(col).type(type).build());
        em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId())).showtime(showtime).seat(seat).build());
        return seat.getId();
    }
}
