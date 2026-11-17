package com.movie.ticket_booking.service;

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

/** B4 on H2: a voucher is frozen into the total, can be changed until payment starts, and counts as used while its booking lives. */
@DataJpaTest(showSql = false)
@Import({BookingServiceImpl.class, BookingLifecycleServiceImpl.class, BookingCreationServiceImpl.class, BookingExtrasCalculator.class,
        BookingExtrasServiceImpl.class, InventoryServiceImpl.class, PricingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingVoucherTest {
    @Autowired private BookingCreationService creation;
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private BookingExtrasService extras;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String email;
    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private List<Long> seats;
    private long comboId;
    private String tag;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            tag = "VT" + suffix.substring(0, 6).toUpperCase();
            email = suffix + "@example.invalid";
            User user = em.persist(User.builder().username(suffix).email(email).passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Voucher test").totalRows(1).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Voucher test").slug(suffix).posterUrl("https://example.invalid/p")
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
            comboId = em.persist(Combo.builder().code(tag + "C").name("Combo").price(79_000L).build()).getId();
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
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM booking_promotion WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM booking_combo WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        jdbc.update("DELETE FROM promotion WHERE code LIKE ?", tag + "%");
        jdbc.update("DELETE FROM combo WHERE id=?", comboId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
        });
    }

    private String promo(String suffix, PromotionKind kind, Integer percent, Long max, Long fixed, boolean first, boolean once) {
        String code = tag + suffix;
        jdbc.update("INSERT INTO promotion (code,title,kind,percent_off,max_discount,fixed_price,first_order_only,once_per_user,active,sort_order,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,1,0,?,?)",
                code, "t", kind.name(), percent, max, fixed, first, once, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return code;
    }

    private CreateBookingResponse create(String voucher, ComboLineRequest... combos) {
        return creation.create(email, new CreateBookingRequest(showtimeId, seats, List.of(combos), voucher));
    }

    private static long seatsTotal(CreateBookingResponse r) {
        return r.seats().stream().mapToLong(CreateBookingResponse.Seat::price).sum();
    }

    private void freeSeats() {
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE', booking_id=NULL, held_until=NULL WHERE showtime_id=?", showtimeId);
    }

    private long col(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void aPercentVoucherIsFrozenIntoTheTotal() {
        String code = promo("P", PromotionKind.PERCENT_OFF, 20, 50_000L, null, false, false);

        CreateBookingResponse r = create(code.toLowerCase(), new ComboLineRequest(comboId, 1));

        long expected = Math.min(50_000L, (seatsTotal(r) * 20 / 100 + 500) / 1000 * 1000);
        assertEquals(expected, r.discountAmount());
        assertEquals(code, r.voucherCode());
        assertEquals(seatsTotal(r) + 79_000L - expected, r.totalAmount(), "the snack is not discounted");
        assertEquals(expected, col("SELECT discount_amount FROM booking WHERE id=?", r.bookingId()));
        assertEquals(expected, col("SELECT discount FROM booking_promotion WHERE booking_id=?", r.bookingId()));
        BookingDetailResponse detail = lifecycle.get(email, r.bookingId());
        assertEquals(code, detail.voucherCode());
        assertEquals(r.totalAmount(), detail.totalAmount());
    }

    @Test
    void aRefusedVoucherCreatesNothingAndHoldsNothing() {
        BusinessException e = assertThrows(BusinessException.class, () -> create("NOPE"));
        assertEquals("VOUCHER_INVALID", e.getCode());
        assertEquals(0L, col("SELECT COUNT(*) FROM booking WHERE showtime_id=?", showtimeId));
        assertEquals(0L, col("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='HELD'", showtimeId));
    }

    @Test
    void aCounterPerkCannotBeAppliedOnline() {
        String code = promo("K", PromotionKind.COUNTER_PERK, null, null, null, false, false);
        assertEquals("VOUCHER_INVALID", assertThrows(BusinessException.class, () -> create(code)).getCode());
    }

    @Test
    void aFixedTicketPriceDiscountsEverySeat() {
        String code = promo("F", PromotionKind.FIXED_TICKET_PRICE, null, null, 50_000L, false, false);
        CreateBookingResponse r = create(code);
        long expected = r.seats().stream().mapToLong(s -> Math.max(0, s.price() - 50_000L)).sum();
        assertEquals(expected, r.discountAmount());
        assertEquals(seatsTotal(r) - expected, r.totalAmount());
    }

    @Test
    void aOnceOnlyVoucherIsUsedUpWhileItsBookingIsPaidButFreeAgainIfThatBookingEnds() {
        String code = promo("O", PromotionKind.PERCENT_OFF, 10, null, null, false, true);
        CreateBookingResponse first = create(code);

        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", first.bookingId());
        freeSeats(); // as if the second order were for another show
        BusinessException used = assertThrows(BusinessException.class, () -> create(code));
        assertEquals("VOUCHER_NOT_ELIGIBLE", used.getCode());

        jdbc.update("UPDATE booking SET status='CANCELLED' WHERE id=?", first.bookingId());
        freeSeats();
        assertNotNull(create(code).voucherCode(), "a voucher whose booking was cancelled can be used again");
    }

    @Test
    void aFirstOrderVoucherIsRefusedAfterAPaidOrder() {
        String code = promo("1", PromotionKind.PERCENT_OFF, 10, null, null, true, false);
        CreateBookingResponse earlier = create(null);
        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", earlier.bookingId());
        freeSeats();
        assertEquals("VOUCHER_NOT_ELIGIBLE", assertThrows(BusinessException.class, () -> create(code)).getCode());
    }

    @Test
    void extrasCanAddChangeAndRemoveTheVoucherAndSnacksBeforePayment() {
        String code = promo("E", PromotionKind.PERCENT_OFF, 10, null, null, false, false);
        CreateBookingResponse r = create(null);
        long seats = seatsTotal(r);
        assertEquals(seats, r.totalAmount());

        BookingExtrasResponse both = extras.update(email, r.bookingId(), new BookingExtrasRequest(List.of(new ComboLineRequest(comboId, 2)), code));
        long discount = (seats * 10 / 100 + 500) / 1000 * 1000;
        assertEquals(seats + 158_000L - discount, both.totalAmount());
        assertEquals(discount, both.discountAmount());
        assertEquals(158_000L, both.comboAmount());
        assertEquals(both.totalAmount(), col("SELECT total_amount FROM booking WHERE id=?", r.bookingId()));
        assertEquals(1L, col("SELECT COUNT(*) FROM booking_promotion WHERE booking_id=?", r.bookingId()));

        BookingExtrasResponse none = extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null));
        assertEquals(seats, none.totalAmount());
        assertEquals(0L, none.discountAmount());
        assertEquals(0L, col("SELECT COUNT(*) FROM booking_promotion WHERE booking_id=?", r.bookingId()));
        assertEquals(0L, col("SELECT COUNT(*) FROM booking_combo WHERE booking_id=?", r.bookingId()));
    }

    @Test
    void reapplyingTheSameOnceOnlyVoucherToTheSameBookingIsNotCountedAgainstItself() {
        String code = promo("R", PromotionKind.PERCENT_OFF, 10, null, null, false, true);
        CreateBookingResponse r = create(code);
        BookingExtrasResponse again = extras.update(email, r.bookingId(), new BookingExtrasRequest(null, code));
        assertEquals(code, again.voucherCode());
    }

    @Test
    void aRefusedExtrasChangeLeavesTheBookingUntouched() {
        CreateBookingResponse r = create(null);
        assertEquals("VOUCHER_INVALID", assertThrows(BusinessException.class,
                () -> extras.update(email, r.bookingId(), new BookingExtrasRequest(List.of(new ComboLineRequest(comboId, 1)), "NOPE"))).getCode());
        assertEquals(r.totalAmount(), col("SELECT total_amount FROM booking WHERE id=?", r.bookingId()));
        assertEquals(0L, col("SELECT COUNT(*) FROM booking_combo WHERE booking_id=?", r.bookingId()));
    }

    @Test
    void onceAPaymentAttemptExistsTheTotalIsFinal() {
        CreateBookingResponse r = create(null);
        jdbc.update("INSERT INTO payment_transaction (booking_id,provider,txn_ref,amount,status,created_at) VALUES (?,?,?,?,?,?)",
                r.bookingId(), "VNPAY", r.bookingId() + "_1", r.totalAmount(), "PENDING", Timestamp.from(Instant.now()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> extras.update(email, r.bookingId(), new BookingExtrasRequest(List.of(new ComboLineRequest(comboId, 1)), null)));

        assertEquals("PAYMENT_ALREADY_STARTED", e.getCode());
        assertEquals(r.totalAmount(), col("SELECT total_amount FROM booking WHERE id=?", r.bookingId()));
    }

    @Test
    void onlyAPendingUnexpiredBookingOfTheCallerCanChange() {
        CreateBookingResponse r = create(null);
        String stranger = "s" + UUID.randomUUID().toString().substring(0, 8) + "@example.invalid";
        new TransactionTemplate(transactions).executeWithoutResult(s ->
                em.persist(User.builder().username(stranger.substring(0, 9)).email(stranger).passwordHash("x").build()));
        try {
            assertEquals("FORBIDDEN", assertThrows(BusinessException.class,
                    () -> extras.update(stranger, r.bookingId(), new BookingExtrasRequest(null, null))).getCode());

            jdbc.update("UPDATE booking SET hold_expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(5)), r.bookingId());
            assertEquals("HOLD_EXPIRED", assertThrows(BusinessException.class,
                    () -> extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null))).getCode());

            jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", r.bookingId());
            assertEquals("BOOKING_NOT_EDITABLE", assertThrows(BusinessException.class,
                    () -> extras.update(email, r.bookingId(), new BookingExtrasRequest(null, null))).getCode());

            assertEquals("BOOKING_NOT_FOUND", assertThrows(BusinessException.class,
                    () -> extras.update(email, 987_654_321L, new BookingExtrasRequest(null, null))).getCode());
        } finally {
            jdbc.update("DELETE FROM users WHERE email=?", stranger);
        }
    }
}
