package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.service.BookingExpiryService;
import com.movie.ticket_booking.service.inventory.InventoryService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL: set-based sweep SQL is valid, and sweeping while a payment lands neither deadlocks nor loses it. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class SweeperMysqlTest {
    private static final long USER_ID = 94_000L;

    @Autowired private BookingExpiryService expiry;
    @Autowired private ApplyPaymentResultUseCase payments;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;
    @Value("${vnpay.hash-secret}") private String secret;
    @Value("${vnpay.tmn-code}") private String tmn;

    private long showtimeId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        cleanUp();
        showtimeId = jdbc.queryForObject("SELECT MIN(id) FROM showtime", Long.class);
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE showtime_id=?", showtimeId);
        seatIds = jdbc.queryForList("SELECT seat_id FROM showtime_seat WHERE showtime_id=? ORDER BY seat_id LIMIT 2", Long.class, showtimeId);
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                USER_ID, "sweep-test", "sweep-test@example.invalid");
    }

    @AfterEach
    void cleanUp() {
        for (Long id : jdbc.queryForList("SELECT id FROM booking WHERE user_id=?", Long.class, USER_ID)) {
            jdbc.update("DELETE FROM tickets WHERE booking_id=?", id);
            jdbc.update("DELETE FROM booking_status_history WHERE booking_id=?", id);
            jdbc.update("DELETE FROM payment_transaction WHERE booking_id=?", id);
            jdbc.update("DELETE FROM booking_seat WHERE booking_id=?", id);
            jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE booking_id=?", id);
        }
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE status='SOLD' AND showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE user_id=?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id=?", USER_ID);
    }

    private long booking(String holdExpression) {
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PENDING',200000," + holdExpression + ")", USER_ID, showtimeId);
        long id = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, USER_ID);
        for (Long seatId : seatIds) jdbc.update("INSERT INTO booking_seat (booking_id,seat_id,price) VALUES (?,?,100000)", id, seatId);
        return id;
    }

    @Test
    void overdueBookingIsExpiredAndSeatsFreedByTheSetBasedSql() {
        long id = booking("UTC_TIMESTAMP() + INTERVAL 10 MINUTE");
        inventory.claim(showtimeId, seatIds, id, Instant.now().plusSeconds(600));
        jdbc.update("UPDATE booking SET hold_expires_at=UTC_TIMESTAMP() - INTERVAL 1 MINUTE WHERE id=?", id);
        jdbc.update("UPDATE showtime_seat SET held_until=UTC_TIMESTAMP() - INTERVAL 1 MINUTE WHERE booking_id=?", id);

        BookingExpiryService.SweepResult first = expiry.expireOverdue(Instant.now());
        BookingExpiryService.SweepResult second = expiry.expireOverdue(Instant.now());

        assertEquals(1, first.expiredBookings());
        assertTrue(first.releasedSeats() >= 2);
        assertEquals(0, second.expiredBookings());
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_status_history WHERE booking_id=? AND to_status='EXPIRED'", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status<>'AVAILABLE'", Integer.class, showtimeId));
    }

    @Test
    void sweepingWhilePaymentsLandDoesNotDeadlockOrLoseThePayment() throws Exception {
        long id = booking("UTC_TIMESTAMP() + INTERVAL 10 MINUTE");
        inventory.claim(showtimeId, seatIds, id, Instant.now().plusSeconds(600));
        String ref = id + "_1";
        jdbc.update("INSERT INTO payment_transaction (booking_id,provider,txn_ref,amount,status) VALUES (?,'VNPAY',?,200000,'PENDING')", id, ref);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> sweeping = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 40; i++) expiry.expireOverdue(Instant.now());
                return null;
            });
            Future<PaymentOutcome> paying = pool.submit(() -> {
                start.await();
                return payments.apply(VnPayTestSigner.signed(secret, tmn, ref, 20_000_000L, "00", "00")).outcome();
            });
            start.countDown();
            sweeping.get(60, TimeUnit.SECONDS);
            assertEquals(PaymentOutcome.CONFIRMED, paying.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id=?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='SOLD'", Integer.class, showtimeId));
    }
}
