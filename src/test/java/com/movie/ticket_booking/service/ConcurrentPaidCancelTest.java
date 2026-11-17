package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CheckInRequest;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.inventory.InventoryService;
import com.movie.ticket_booking.service.ticket.CheckInService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL: double-tapped cancel refunds once, and a cancel racing a door scan never leaves "refunded but used". */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class ConcurrentPaidCancelTest {
    private static final long CUSTOMER = 99_000L;
    private static final long STAFF = 99_001L;
    private static final String CUSTOMER_EMAIL = "paidcancel@example.invalid";
    private static final String STAFF_EMAIL = "paidcancel-staff@example.invalid";

    @Autowired private PaidBookingCancellationService cancellation;
    @Autowired private CheckInService checkIn;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;
    private long showtimeId;
    private long bookingId;

    @BeforeEach
    void seed() {
        cleanUp();
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())", CUSTOMER, "paidcancel", CUSTOMER_EMAIL);
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())", STAFF, "paidcancel-staff", STAFF_EMAIL);
        // Starts in 61 minutes: both the cancel window (>= 60 min) and the check-in window (opens 60 min before) are open.
        jdbc.update("""
                INSERT INTO showtime (movie_id,room_id,start_time,end_time,base_price,created_at,updated_at,status)
                SELECT movie_id,1,UTC_TIMESTAMP() + INTERVAL 61 MINUTE,UTC_TIMESTAMP() + INTERVAL 181 MINUTE,100000,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'SCHEDULED'
                FROM showtime WHERE id=1""");
        showtimeId = jdbc.queryForObject("SELECT MAX(id) FROM showtime", Long.class);
        inventory.initializeShowtime(showtimeId);
        long seatId = jdbc.queryForObject("SELECT MIN(id) FROM seat WHERE room_id=1 AND active=1", Long.class);
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PAID',100000,UTC_TIMESTAMP())", CUSTOMER, showtimeId);
        bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, CUSTOMER);
        jdbc.update("INSERT INTO tickets (booking_id,showtime_id,seat_id,price,code,created_at,updated_at) VALUES (?,?,?,100000,'TCK-paidcancel',UTC_TIMESTAMP(),UTC_TIMESTAMP())", bookingId, showtimeId, seatId);
        jdbc.update("UPDATE showtime_seat SET status='SOLD' WHERE showtime_id=? AND seat_id=?", showtimeId, seatId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM tickets WHERE code='TCK-paidcancel'");
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (SELECT id FROM booking WHERE user_id=?)", CUSTOMER);
        jdbc.update("DELETE FROM refund_queue WHERE booking_id IN (SELECT id FROM booking WHERE user_id=?)", CUSTOMER);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id IN (SELECT showtime_id FROM booking WHERE user_id=?)", CUSTOMER);
        jdbc.update("DELETE FROM booking WHERE user_id=?", CUSTOMER);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id > 1 AND showtime_id IN (SELECT id FROM showtime WHERE id > 1 AND base_price=100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 30 MINUTE AND start_time < UTC_TIMESTAMP() + INTERVAL 90 MINUTE)");
        jdbc.update("DELETE FROM showtime WHERE id > 1 AND base_price=100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 30 MINUTE AND start_time < UTC_TIMESTAMP() + INTERVAL 90 MINUTE");
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", CUSTOMER, STAFF);
    }

    @Test
    void fiveTapsRefundOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<String>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        cancellation.cancel(CUSTOMER_EMAIL, bookingId);
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : calls) outcomes.add(f.get(60, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("OK"::equals).count(), outcomes.toString());
            assertEquals(4, outcomes.stream().filter("BOOKING_NOT_PAID"::equals).count(), outcomes.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, bookingId));
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
    }

    /**
     * The customer cancels while staff scan the ticket. Either the scan wins (cancel refused, ticket used, booking PAID)
     * or the cancel wins (booking REFUNDED and the scan is refused). Never both.
     */
    @Test
    void cancelRacingADoorScanNeverEndsRefundedAndUsed() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> cancel = pool.submit(() -> {
                start.await();
                try {
                    cancellation.cancel(CUSTOMER_EMAIL, bookingId);
                    return "OK";
                } catch (BusinessException e) {
                    return e.getCode();
                }
            });
            Future<String> scan = pool.submit(() -> {
                start.await();
                try {
                    checkIn.checkIn(STAFF_EMAIL, new CheckInRequest(null, "TCK-paidcancel"));
                    return "OK";
                } catch (BusinessException e) {
                    return e.getCode();
                }
            });
            start.countDown();
            String cancelResult = cancel.get(60, TimeUnit.SECONDS);
            String scanResult = scan.get(60, TimeUnit.SECONDS);

            boolean used = jdbc.queryForObject("SELECT checked_in_at IS NOT NULL FROM tickets WHERE code='TCK-paidcancel'", Boolean.class);
            String status = jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId);
            assertFalse(used && "REFUNDED".equals(status), "refunded and used: cancel=" + cancelResult + " scan=" + scanResult);
            assertTrue(used || "REFUNDED".equals(status), "neither happened: cancel=" + cancelResult + " scan=" + scanResult);
            if (used) {
                assertEquals("TICKET_ALREADY_USED", cancelResult);
            } else {
                assertEquals("TICKET_NOT_VALID", scanResult);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
