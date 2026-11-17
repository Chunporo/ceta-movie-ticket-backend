package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.dto.booking.RefundResponse;
import com.movie.ticket_booking.entity.enums.RefundStatus;
import com.movie.ticket_booking.exception.SeatConflictException;
import com.movie.ticket_booking.service.inventory.InventoryService;
import com.movie.ticket_booking.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BASELINE Gate C, section 9: the four incident drills, run end to end on real MySQL through the real services
 * (booking creation, payment URL, IPN/return use case, sweeper, refund queue), checking what the database ends up as.
 */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class GateCIncidentRehearsalTest {
    private static final long USER_A = 98_000L;
    private static final long USER_B = 98_001L;
    private static final String EMAIL_A = "gatec-a@example.invalid";
    private static final String EMAIL_B = "gatec-b@example.invalid";

    @Autowired private BookingCreationService bookings;
    @Autowired private PaymentUrlService paymentUrls;
    @Autowired private ApplyPaymentResultUseCase payments;
    @Autowired private BookingExpiryService sweeper;
    @Autowired private RefundService refunds;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;
    @Value("${vnpay.hash-secret}") private String secret;
    @Value("${vnpay.tmn-code}") private String tmn;

    private long showtimeId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        cleanUp();
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())", USER_A, "gatec-a", EMAIL_A);
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())", USER_B, "gatec-b", EMAIL_B);
        jdbc.update("""
                INSERT INTO showtime (movie_id,room_id,start_time,end_time,base_price,created_at,updated_at,status)
                SELECT movie_id,1,UTC_TIMESTAMP() + INTERVAL 5 DAY,UTC_TIMESTAMP() + INTERVAL 5 DAY + INTERVAL 2 HOUR,100000,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'SCHEDULED'
                FROM showtime WHERE id=1""");
        showtimeId = jdbc.queryForObject("SELECT MAX(id) FROM showtime", Long.class);
        inventory.initializeShowtime(showtimeId);
        // Plain STANDARD seats only, so a booking is never refused for the couple-seat rule.
        seatIds = jdbc.queryForList("SELECT s.id FROM seat s WHERE s.room_id=1 AND s.active=1 AND s.type='STANDARD' ORDER BY s.id LIMIT 4", Long.class);
        assertEquals(4, seatIds.size(), "room 1 needs four STANDARD seats");
    }

    @AfterEach
    void cleanUp() {
        String mine = "SELECT id FROM booking WHERE user_id IN (" + USER_A + "," + USER_B + ")";
        jdbc.update("DELETE FROM notification WHERE user_id IN (" + USER_A + "," + USER_B + ")");
        jdbc.update("DELETE FROM tickets WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM refund_queue WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM showtime_seat WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id IN (SELECT showtime_id FROM booking WHERE user_id IN (" + USER_A + "," + USER_B + "))");
        jdbc.update("DELETE FROM booking WHERE user_id IN (?,?)", USER_A, USER_B);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id > 1 AND showtime_id IN (SELECT id FROM showtime WHERE id > 1 AND base_price=100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 4 DAY AND start_time < UTC_TIMESTAMP() + INTERVAL 6 DAY)");
        jdbc.update("DELETE FROM showtime WHERE id > 1 AND base_price=100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 4 DAY AND start_time < UTC_TIMESTAMP() + INTERVAL 6 DAY");
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", USER_A, USER_B);
    }

    private CreateBookingResponse book(String email, Long... seats) {
        return bookings.create(email, new CreateBookingRequest(showtimeId, List.of(seats)));
    }

    private Map<String, String> vnpay(long bookingId, int attempt, long amountVnd) {
        return VnPayTestSigner.signed(secret, tmn, bookingId + "_" + attempt, amountVnd * 100, "00", "00");
    }

    private String seatStatus(long seatId) {
        return jdbc.queryForObject("SELECT status FROM showtime_seat WHERE showtime_id=? AND seat_id=?", String.class, showtimeId, seatId);
    }

    private String bookingStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, id);
    }

    /** Ten minutes pass: the booking's hold and its seat holds are now in the past (inventory reads the real clock). */
    private void holdTimesOut(long bookingId) {
        jdbc.update("UPDATE booking SET hold_expires_at = UTC_TIMESTAMP() - INTERVAL 1 MINUTE WHERE id=?", bookingId);
        jdbc.update("UPDATE showtime_seat SET held_until = UTC_TIMESTAMP() - INTERVAL 1 MINUTE WHERE booking_id=? AND status='HELD'", bookingId);
    }

    private int notifications(long userId, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND type=?", Integer.class, userId, type);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** Drill 1a: the IPN never reaches us, the customer's return URL does; VNPay's retried IPN then changes nothing. */
    @Test
    void ipnLostThenReturnUrlConfirmsAndTheLateIpnIsHarmless() {
        CreateBookingResponse b = book(EMAIL_A, seatIds.get(0), seatIds.get(1));
        paymentUrls.create(EMAIL_A, b.bookingId(), "127.0.0.1");
        Map<String, String> callback = vnpay(b.bookingId(), 1, b.totalAmount());

        assertEquals(PaymentOutcome.CONFIRMED, payments.apply(callback).outcome());   // return URL, same use case
        for (int retry = 0; retry < 3; retry++) {                                     // VNPay's IPN retries
            assertEquals(PaymentOutcome.ALREADY_PROCESSED, payments.apply(callback).outcome());
        }

        assertEquals("PAID", bookingStatus(b.bookingId()));
        assertEquals(2, count("SELECT COUNT(*) FROM tickets WHERE booking_id=?", b.bookingId()));
        assertEquals("SOLD", seatStatus(seatIds.get(0)));
        assertEquals(0, count("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", b.bookingId()));
        assertEquals(1, notifications(USER_A, "ORDER_PAID"), "one notification however many times the IPN was retried");
    }

    /** Drill 1b: neither callback arrives in time; the hold lapses, then a late IPN must queue a refund, not sell seats. */
    @Test
    void ipnArrivesAfterTheHoldLapsedQueuesARefundAndSellsNothing() {
        CreateBookingResponse b = book(EMAIL_A, seatIds.get(0));
        paymentUrls.create(EMAIL_A, b.bookingId(), "127.0.0.1");

        holdTimesOut(b.bookingId());
        sweeper.expireOverdue(Instant.now());
        assertEquals("EXPIRED", bookingStatus(b.bookingId()));
        assertEquals("AVAILABLE", seatStatus(seatIds.get(0)));

        // Someone else takes the seat while the first customer's money is still in flight.
        CreateBookingResponse other = book(EMAIL_B, seatIds.get(0));
        assertEquals(PaymentOutcome.REFUND_QUEUED, payments.apply(vnpay(b.bookingId(), 1, b.totalAmount())).outcome());

        assertEquals(0, count("SELECT COUNT(*) FROM tickets WHERE booking_id=?", b.bookingId()));
        assertEquals(1, count("SELECT COUNT(*) FROM refund_queue WHERE booking_id=? AND status='PENDING'", b.bookingId()));
        assertEquals("HELD", seatStatus(seatIds.get(0)), "the new holder keeps the seat");
        assertEquals("PENDING", bookingStatus(other.bookingId()));
        assertEquals(1, notifications(USER_A, "REFUND_PENDING"));
        assertEquals(0, notifications(USER_A, "ORDER_PAID"));
    }

    /** Drill 2: the app is killed mid-payment. Nothing calls us again; the sweeper alone must free everything. */
    @Test
    void appKilledMidPaymentSeatsComeBackAndTheUserCanBookAgain() {
        CreateBookingResponse b = book(EMAIL_A, seatIds.get(0), seatIds.get(1));
        paymentUrls.create(EMAIL_A, b.bookingId(), "127.0.0.1");
        assertEquals("HELD", seatStatus(seatIds.get(0)));
        // Still within the hold: the sweeper must leave it alone.
        sweeper.expireOverdue(Instant.now());
        assertEquals("PENDING", bookingStatus(b.bookingId()));

        holdTimesOut(b.bookingId());
        BookingExpiryService.SweepResult swept = sweeper.expireOverdue(Instant.now());

        assertTrue(swept.expiredBookings() >= 1);
        assertEquals("EXPIRED", bookingStatus(b.bookingId()));
        assertEquals("AVAILABLE", seatStatus(seatIds.get(0)));
        assertEquals("AVAILABLE", seatStatus(seatIds.get(1)));
        CreateBookingResponse again = book(EMAIL_A, seatIds.get(0), seatIds.get(1));
        assertEquals("PENDING", bookingStatus(again.bookingId()));
    }

    /** Drill 3: two phones tap the same seat in the same instant. One wins, the other gets a 409 and can pick again. */
    @Test
    void twoPhonesOneSeatExactlyOneWins() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> calls = new ArrayList<>();
            for (String email : List.of(EMAIL_A, EMAIL_B)) {
                calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        book(email, seatIds.get(2));
                        return "OK";
                    } catch (SeatConflictException e) {
                        assertEquals(List.of(seatIds.get(2)), e.getConflictSeatIds());
                        return "CONFLICT";
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : calls) outcomes.add(f.get(60, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(outcomes, "OK"), outcomes.toString());
            assertEquals(1, Collections.frequency(outcomes, "CONFLICT"), outcomes.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("SELECT COUNT(*) FROM booking WHERE showtime_id=? AND status='PENDING'", showtimeId), "the loser's booking was rolled back");
        assertEquals("HELD", seatStatus(seatIds.get(2)));
        // The loser picks another seat and gets it.
        String loser = count("SELECT COUNT(*) FROM booking WHERE user_id=? AND showtime_id=?", USER_A, showtimeId) == 0 ? EMAIL_A : EMAIL_B;
        assertEquals("PENDING", bookingStatus(book(loser, seatIds.get(3)).bookingId()));
    }

    /** Drill 4: one refund by hand, from the late payment to the admin marking it done. */
    @Test
    void manualRefundOfALatePaymentEndsRefunded() {
        CreateBookingResponse b = book(EMAIL_A, seatIds.get(0));
        paymentUrls.create(EMAIL_A, b.bookingId(), "127.0.0.1");
        holdTimesOut(b.bookingId());
        sweeper.expireOverdue(Instant.now());
        assertEquals(PaymentOutcome.REFUND_QUEUED, payments.apply(vnpay(b.bookingId(), 1, b.totalAmount())).outcome());

        RefundResponse pending = refunds.list(RefundStatus.PENDING).stream()
                .filter(r -> r.bookingId().equals(b.bookingId())).findFirst().orElseThrow();
        assertEquals("LATE_PAYMENT", pending.reason());
        assertEquals(b.totalAmount(), pending.amount());

        RefundResponse done = refunds.markDone(pending.id(), "Refunded in VNPay merchant portal");

        assertEquals("DONE", done.status());
        assertNotNull(done.doneAt());
        assertEquals("REFUNDED", bookingStatus(b.bookingId()));
        assertEquals(0, count("SELECT COUNT(*) FROM tickets WHERE booking_id=?", b.bookingId()));
        assertEquals(1, notifications(USER_A, "REFUND_PENDING"));
        assertEquals(1, notifications(USER_A, "REFUND_DONE"));
    }
}
