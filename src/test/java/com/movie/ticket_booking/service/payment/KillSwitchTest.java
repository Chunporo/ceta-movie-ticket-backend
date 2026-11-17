package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.SeatConflictException;
import com.movie.ticket_booking.service.*;
import com.movie.ticket_booking.service.inventory.InventoryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** D-08: with app.booking.hold-enabled=false the same flows work, claiming at payment time. */
@DataJpaTest(showSql = false)
@Import({ApplyPaymentResultUseCaseImpl.class, VNPayService.class, InventoryServiceImpl.class, BookingCreationServiceImpl.class, com.movie.ticket_booking.service.BookingExtrasCalculator.class,
        PricingService.class, RefundServiceImpl.class, BookingExpiryServiceImpl.class})
@TestPropertySource(properties = "app.booking.hold-enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KillSwitchTest {
    @Autowired private ApplyPaymentResultUseCase payments;
    @Autowired private BookingCreationService creation;
    @Autowired private BookingExpiryService expiry;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Value("${vnpay.hash-secret}") private String secret;
    @Value("${vnpay.tmn-code}") private String tmn;

    private String email;
    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            email = suffix + "@example.invalid";
            userId = em.persist(User.builder().username(suffix).email(email).passwordHash("x").build()).getId();
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Kill switch test").totalRows(1).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Kill switch test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            seatIds = new java.util.ArrayList<>();
            for (int col = 1; col <= 2; col++) {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).build());
                em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId())).showtime(showtime).seat(seat).build());
                seatIds.add(seat.getId());
            }
            em.flush();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
    }

    @AfterEach
    void clean() {
        String bookingIds = "SELECT id FROM booking WHERE showtime_id=" + showtimeId;
        jdbc.update("DELETE FROM refund_queue WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM tickets WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + bookingIds + ")");
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

    private CreateBookingResponse book() {
        CreateBookingResponse booking = creation.create(email, new CreateBookingRequest(showtimeId, seatIds));
        jdbc.update("INSERT INTO payment_transaction (booking_id,provider,txn_ref,amount,status,created_at) VALUES (?,?,?,?,?,?)",
                booking.bookingId(), "VNPAY", booking.bookingId() + "_1", booking.totalAmount(), "PENDING", Timestamp.from(Instant.now()));
        return booking;
    }

    private PaymentOutcome pay(CreateBookingResponse booking) {
        return payments.apply(VnPayTestSigner.signed(secret, tmn, booking.bookingId() + "_1", booking.totalAmount() * 100, "00", "00")).outcome();
    }

    @Test
    void creatingABookingHoldsNothing() {
        CreateBookingResponse booking = book();

        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, booking.bookingId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status<>'AVAILABLE'", Integer.class, showtimeId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM booking_seat WHERE booking_id=?", Integer.class, booking.bookingId()));
    }

    @Test
    void anAlreadySoldSeatIsStillRefusedAtCreation() {
        jdbc.update("UPDATE showtime_seat SET status='SOLD' WHERE showtime_id=? AND seat_id=?", showtimeId, seatIds.get(1));

        SeatConflictException ex = assertThrows(SeatConflictException.class,
                () -> creation.create(email, new CreateBookingRequest(showtimeId, seatIds)));

        assertEquals(List.of(seatIds.get(1)), ex.getConflictSeatIds());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE showtime_id=?", Integer.class, showtimeId));
    }

    @Test
    void paymentClaimsFromAvailableAndIssuesTicketsOnce() {
        CreateBookingResponse booking = book();

        assertEquals(PaymentOutcome.CONFIRMED, pay(booking));
        assertEquals(PaymentOutcome.ALREADY_PROCESSED, pay(booking));

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id=?", Integer.class, booking.bookingId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE status='SOLD' AND showtime_id=?", Integer.class, showtimeId));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, booking.bookingId()));
    }

    @Test
    void paymentForASeatSoldMeanwhileQueuesARefund() {
        CreateBookingResponse booking = book();
        jdbc.update("UPDATE showtime_seat SET status='SOLD' WHERE showtime_id=? AND seat_id=?", showtimeId, seatIds.get(0));

        assertEquals(PaymentOutcome.REFUND_QUEUED, pay(booking));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE showtime_id=?", Integer.class, showtimeId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, booking.bookingId()));
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, booking.bookingId()));
    }

    @Test
    void sweeperExpiresBookingsButDoesNotTouchSeats() {
        CreateBookingResponse booking = book();
        jdbc.update("UPDATE booking SET hold_expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(30)), booking.bookingId());
        jdbc.update("UPDATE showtime_seat SET status='HELD',booking_id=?,held_until=? WHERE showtime_id=? AND seat_id=?",
                booking.bookingId(), Timestamp.from(Instant.now().minusSeconds(30)), showtimeId, seatIds.get(0));

        BookingExpiryService.SweepResult result = expiry.expireOverdue(Instant.now());

        assertEquals(new BookingExpiryService.SweepResult(1, 0), result);
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, booking.bookingId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE status='HELD' AND showtime_id=?", Integer.class, showtimeId));
    }
}
