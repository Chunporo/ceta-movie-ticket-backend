package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.LoyaltyAdjustRequest;
import com.movie.ticket_booking.dto.LoyaltySummaryResponse;
import com.movie.ticket_booking.dto.booking.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** B5 on H2: points are reserved by a live booking, come back when it ends unpaid, and are earned only by a PAID one. */
@DataJpaTest(showSql = false)
@Import({BookingServiceImpl.class, BookingLifecycleServiceImpl.class, BookingCreationServiceImpl.class, BookingExtrasCalculator.class,
        BookingExtrasServiceImpl.class, InventoryServiceImpl.class, PricingService.class, LoyaltyServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingPointsTest {
    @Autowired private BookingCreationService creation;
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private BookingExtrasService extras;
    @Autowired private LoyaltyService loyalty;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String email;
    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private List<Long> seats;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            email = suffix + "@example.invalid";
            User user = em.persist(User.builder().username(suffix).email(email).passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Points test").totalRows(1).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Points test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            List<Long> ids = new ArrayList<>();
            for (int col = 1; col <= 2; col++) {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).build());
                em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId())).showtime(showtime).seat(seat).build());
                ids.add(seat.getId());
            }
            em.flush();
            seats = ids;
            userId = user.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
    }

    @AfterEach
    void clean() {
        String bookings = "SELECT id FROM booking WHERE showtime_id=" + showtimeId;
        jdbc.update("DELETE FROM loyalty_entry WHERE user_id=?", userId);
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM booking_promotion WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM booking_combo WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + bookings + ")");
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

    private void grant(int points) {
        loyalty.adjust(new LoyaltyAdjustRequest(email, points, "test grant"));
    }

    private long balance() {
        return loyalty.summary(email).balance();
    }

    private CreateBookingResponse create() {
        return creation.create(email, new CreateBookingRequest(showtimeId, seats));
    }

    private long col(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void spendingABlockTakesPointsOffTheTotalAndReservesThem() {
        grant(2500);
        CreateBookingResponse r = create();

        BookingExtrasResponse paid = extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null, true));

        assertEquals(20_000L, paid.pointsAmount());
        assertEquals(2000, paid.pointsUsed());
        assertEquals(r.totalAmount() - 20_000L, paid.totalAmount());
        assertEquals(500, balance(), "the points are reserved while the booking is pending");
        BookingDetailResponse detail = lifecycle.get(email, r.bookingId());
        assertEquals(20_000L, detail.pointsAmount());
        assertEquals(paid.totalAmount(), detail.totalAmount());
    }

    @Test
    void notEnoughPointsIsRefusedAndChangesNothing() {
        grant(1999);
        CreateBookingResponse r = create();
        BusinessException e = assertThrows(BusinessException.class,
                () -> extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null, true)));
        assertEquals("INSUFFICIENT_POINTS", e.getCode());
        assertEquals(r.totalAmount(), col("SELECT total_amount FROM booking WHERE id=?", r.bookingId()));
        assertEquals(1999, balance());
    }

    @Test
    void anOrderSmallerThanTheBlockCannotSpendPoints() {
        grant(5000);
        CreateBookingResponse r = create();
        jdbc.update("UPDATE booking_seat SET price=5000 WHERE booking_id=?", r.bookingId()); // 10.000 of seats < 20.000
        BusinessException e = assertThrows(BusinessException.class,
                () -> extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null, true)));
        assertEquals("POINTS_NOT_APPLICABLE", e.getCode());
    }

    @Test
    void turningPointsOffOrEditingAgainGivesThemBackAndNeverSpendsTwice() {
        grant(2500);
        CreateBookingResponse r = create();

        extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null, true));
        extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null, true)); // same booking again
        assertEquals(500, balance(), "one block, not two");
        assertEquals(1L, col("SELECT COUNT(*) FROM loyalty_entry WHERE booking_id=? AND reason='REDEEM'", r.bookingId()));

        BookingExtrasResponse off = extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null, false));
        assertEquals(r.totalAmount(), off.totalAmount());
        assertEquals(0L, off.pointsAmount());
        assertEquals(2500, balance());
    }

    @Test
    void pointsComeBackWhenTheBookingEndsUnpaid() {
        grant(2500);
        CreateBookingResponse r = create();
        extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null, true));
        assertEquals(500, balance());

        jdbc.update("UPDATE booking SET status='EXPIRED' WHERE id=?", r.bookingId());

        assertEquals(2500, balance());
    }

    private CreateBookingResponse creationOnFreshSeats() {
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE', booking_id=NULL, held_until=NULL WHERE showtime_id=?", showtimeId);
        return create();
    }

    @Test
    void aSecondLiveBookingCannotSpendPointsTheFirstOneReserved() {
        grant(2500);
        CreateBookingResponse first = create();
        extras.update(email, first.bookingId(), new BookingExtrasRequest(null, null, true));
        // a second booking of the same customer (the seat guard is bypassed here: only the points rule is under test)
        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", first.bookingId());
        CreateBookingResponse second = creationOnFreshSeats();

        BusinessException e = assertThrows(BusinessException.class,
                () -> extras.update(email, second.bookingId(), new BookingExtrasRequest(null, null, true)));

        assertEquals("INSUFFICIENT_POINTS", e.getCode());
    }

    @Test
    void earnedPointsCountOnlyWhileThePaidBookingStandsAndSetTheTier() {
        CreateBookingResponse r = create();
        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", r.bookingId());
        jdbc.update("INSERT INTO loyalty_entry (user_id,delta,reason,booking_id,created_at) VALUES (?,?,?,?,?)",
                userId, 5200, "EARN", r.bookingId(), Timestamp.from(Instant.now()));

        LoyaltySummaryResponse paid = loyalty.summary(email);
        assertEquals(5200, paid.balance());
        assertEquals("VIP", paid.tier());
        assertNull(paid.nextTier());
        assertEquals(0, paid.pointsToNextTier());

        jdbc.update("UPDATE booking SET status='REFUNDED' WHERE id=?", r.bookingId());
        LoyaltySummaryResponse refunded = loyalty.summary(email);
        assertEquals(0, refunded.balance(), "a refund takes the earned points back");
        assertEquals("MEMBER", refunded.tier());
        assertEquals("VIP", refunded.nextTier());
        assertEquals(5000, refunded.pointsToNextTier());
    }

    @Test
    void spendingDoesNotLowerTheTier() {
        CreateBookingResponse r = create();
        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", r.bookingId());
        jdbc.update("INSERT INTO loyalty_entry (user_id,delta,reason,booking_id,created_at) VALUES (?,?,?,?,?)",
                userId, 5000, "EARN", r.bookingId(), Timestamp.from(Instant.now()));
        grant(-3000);
        LoyaltySummaryResponse s = loyalty.summary(email);
        assertEquals(2000, s.balance());
        assertEquals("VIP", s.tier(), "tier follows what was earned, not what is left");
    }

    @Test
    void summaryShowsTheRedeemBlockAndTheCounters() {
        LoyaltySummaryResponse s = loyalty.summary(email);
        assertEquals(2000, s.redeemBlockPoints());
        assertEquals(20_000L, s.redeemBlockVnd());
        assertEquals(0, s.stats().ticketsWatched());
        assertEquals(0, s.stats().vouchers());
        assertEquals("MEMBER", s.tier());
        assertEquals(5000, s.pointsToNextTier());
    }

    @Test
    void anAdminCannotTakeTheBalanceBelowZeroOrAdjustByNothing() {
        grant(100);
        assertEquals("INSUFFICIENT_POINTS", assertThrows(BusinessException.class,
                () -> loyalty.adjust(new LoyaltyAdjustRequest(email, -101, "oops"))).getCode());
        assertEquals("INVALID_REQUEST", assertThrows(BusinessException.class,
                () -> loyalty.adjust(new LoyaltyAdjustRequest(email, 0, "nothing"))).getCode());
        assertEquals(100, balance());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> loyalty.adjust(new LoyaltyAdjustRequest("nobody@example.invalid", 5, "x")));
    }
}
