package com.movie.ticket_booking.service;

import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.inventory.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MySQL: customers claim seats (booking row first, then the showtime lock, as booking creation does) while an
 * admin cancels the showtime. No deadlock, nothing stays held or paid, every PAID booking has exactly one refund.
 */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class ConcurrentShowtimeCancelTest {
    private static final long FIRST_USER = 97_000L;
    private static final int CLAIMERS = 6;
    private static final int PAID_BOOKINGS = 2;

    @Autowired private ShowtimeCancellationService cancellation;
    @Autowired private InventoryService inventory;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    private long showtimeId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        cleanUp();
        for (int i = 0; i < CLAIMERS + PAID_BOOKINGS; i++) {
            jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                    FIRST_USER + i, "cx-user-" + i, "cx-user-" + i + "@example.invalid");
        }
        jdbc.update("""
                INSERT INTO showtime (movie_id,room_id,start_time,end_time,base_price,created_at,updated_at,status)
                SELECT movie_id,1,UTC_TIMESTAMP() + INTERVAL 3 DAY,UTC_TIMESTAMP() + INTERVAL 3 DAY + INTERVAL 2 HOUR,100000,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'SCHEDULED'
                FROM showtime WHERE id=1""");
        showtimeId = jdbc.queryForObject("SELECT MAX(id) FROM showtime", Long.class);
        inventory.initializeShowtime(showtimeId);
        seatIds = jdbc.queryForList("SELECT id FROM seat WHERE room_id=1 AND active=1 ORDER BY id LIMIT " + (CLAIMERS + PAID_BOOKINGS), Long.class);
        assertEquals(CLAIMERS + PAID_BOOKINGS, seatIds.size());
        // Two bookings already PAID with sold seats.
        for (int i = 0; i < PAID_BOOKINGS; i++) {
            long userId = FIRST_USER + CLAIMERS + i;
            jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PAID',100000,UTC_TIMESTAMP())", userId, showtimeId);
            long bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, userId);
            new TransactionTemplate(transactions).executeWithoutResult(s -> {
                inventory.claim(showtimeId, List.of(seatIds.get((int) (userId - FIRST_USER))), bookingId, Instant.now().plusSeconds(600));
                inventory.promoteToSold(bookingId);
            });
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (SELECT id FROM booking WHERE user_id BETWEEN ? AND ?)", FIRST_USER, FIRST_USER + 20);
        jdbc.update("DELETE FROM refund_queue WHERE booking_id IN (SELECT id FROM booking WHERE user_id BETWEEN ? AND ?)", FIRST_USER, FIRST_USER + 20);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id IN (SELECT id FROM showtime WHERE id > 1 AND start_time > UTC_TIMESTAMP() + INTERVAL 2 DAY AND start_time < UTC_TIMESTAMP() + INTERVAL 4 DAY AND base_price = 100000 AND status IN ('SCHEDULED','CANCELLED') AND id IN (SELECT showtime_id FROM booking WHERE user_id BETWEEN ? AND ?))", FIRST_USER, FIRST_USER + 20);
        jdbc.update("DELETE FROM booking WHERE user_id BETWEEN ? AND ?", FIRST_USER, FIRST_USER + 20);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", FIRST_USER, FIRST_USER + 20);
        // Showtimes this test created have no bookings left; remove the orphans (inventory first).
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id > 1 AND showtime_id IN (SELECT id FROM showtime WHERE id > 1 AND base_price = 100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 2 DAY AND start_time < UTC_TIMESTAMP() + INTERVAL 4 DAY AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.showtime_id = showtime.id))");
        jdbc.update("DELETE FROM showtime WHERE id > 1 AND base_price = 100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 2 DAY AND start_time < UTC_TIMESTAMP() + INTERVAL 4 DAY AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.showtime_id = showtime.id)");
    }

    @Test
    void cancelWhileCustomersAreClaimingLeavesNothingHeldOrPaid() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CLAIMERS + 1);
        List<Future<String>> claims = new ArrayList<>();
        Future<?> cancel;
        try {
            for (int i = 0; i < CLAIMERS; i++) {
                final int n = i;
                claims.add(pool.submit(() -> {
                    start.await();
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(s -> {
                            // Same order as booking creation: own booking row first, then the showtime lock.
                            jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PENDING',100000,UTC_TIMESTAMP() + INTERVAL 10 MINUTE)",
                                    FIRST_USER + n, showtimeId);
                            long bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=? AND showtime_id=?", Long.class, FIRST_USER + n, showtimeId);
                            inventory.claim(showtimeId, List.of(seatIds.get(n)), bookingId, Instant.now().plusSeconds(600));
                        });
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getCode();
                    }
                }));
            }
            cancel = pool.submit(() -> {
                start.await();
                return cancellation.cancel(showtimeId, "Projector broke", "admin@example.invalid");
            });
            start.countDown();
            for (Future<String> f : claims) {
                String outcome = f.get(60, TimeUnit.SECONDS);
                assertTrue(outcome.equals("OK") || outcome.equals("SHOWTIME_CANCELLED"), "unexpected claim outcome " + outcome);
            }
            cancel.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // A claim that committed a hair before the flip may have been picked up late; a second call finishes the job.
        cancellation.cancel(showtimeId, "Projector broke", "admin@example.invalid");

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE showtime_id=? AND status IN ('PENDING','PAID')", Integer.class, showtimeId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='HELD'", Integer.class, showtimeId));
        assertEquals(PAID_BOOKINGS, jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE showtime_id=? AND status='REFUNDED'", Integer.class, showtimeId));
        assertEquals(PAID_BOOKINGS, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE reason='SHOWTIME_CANCELLED' AND booking_id IN (SELECT id FROM booking WHERE showtime_id=?)", Integer.class, showtimeId));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM showtime WHERE id=?", String.class, showtimeId));
    }

    @Test
    void twoAdminsCancellingAtOnceRefundEachBookingOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return cancellation.cancel(showtimeId, "double click", "admin@example.invalid");
                }));
            }
            start.countDown();
            for (Future<?> f : calls) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(PAID_BOOKINGS, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id IN (SELECT id FROM booking WHERE showtime_id=?)", Integer.class, showtimeId));
        assertEquals(PAID_BOOKINGS, jdbc.queryForObject("SELECT COUNT(*) FROM booking_status_history WHERE to_status='REFUNDED' AND booking_id IN (SELECT id FROM booking WHERE showtime_id=?)", Integer.class, showtimeId));
    }
}
