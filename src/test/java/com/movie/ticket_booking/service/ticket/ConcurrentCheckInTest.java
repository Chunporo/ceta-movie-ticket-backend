package com.movie.ticket_booking.service.ticket;

import com.movie.ticket_booking.dto.booking.CheckInRequest;
import com.movie.ticket_booking.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Five phones scan the same QR at once on real MySQL: exactly one is let in. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class ConcurrentCheckInTest {
    private static final long STAFF_ID = 96_000L;
    private static final long CUSTOMER_ID = 96_001L;
    private static final String STAFF_EMAIL = "door-staff@example.invalid";
    private static final int THREADS = 5;

    @Autowired private CheckInService checkIn;
    @Autowired private TicketQrService qr;
    @Autowired private JdbcTemplate jdbc;
    private long showtimeId;
    private long ticketId;

    @BeforeEach
    void seed() {
        cleanUp();
        long seatId = jdbc.queryForObject("SELECT MIN(id) FROM seat WHERE room_id=1", Long.class);
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())", STAFF_ID, "door-staff", STAFF_EMAIL);
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())", CUSTOMER_ID, "door-cust", "door-cust@example.invalid");
        jdbc.update("""
                INSERT INTO showtime (movie_id,room_id,start_time,end_time,base_price,created_at,updated_at)
                SELECT movie_id,1,UTC_TIMESTAMP() + INTERVAL 10 MINUTE,UTC_TIMESTAMP() + INTERVAL 130 MINUTE,100000,UTC_TIMESTAMP(),UTC_TIMESTAMP()
                FROM showtime WHERE id=1""");
        showtimeId = jdbc.queryForObject("SELECT MAX(id) FROM showtime", Long.class);
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PAID',100000,UTC_TIMESTAMP())", CUSTOMER_ID, showtimeId);
        long bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, CUSTOMER_ID);
        jdbc.update("INSERT INTO tickets (booking_id,showtime_id,seat_id,price,code,created_at,updated_at) VALUES (?,?,?,100000,'TCK-door-test',UTC_TIMESTAMP(),UTC_TIMESTAMP())", bookingId, showtimeId, seatId);
        ticketId = jdbc.queryForObject("SELECT id FROM tickets WHERE code='TCK-door-test'", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM tickets WHERE code='TCK-door-test'");
        jdbc.update("DELETE FROM booking WHERE user_id=?", CUSTOMER_ID);
        jdbc.update("DELETE FROM showtime WHERE id > 1 AND start_time > UTC_TIMESTAMP() - INTERVAL 1 HOUR AND start_time < UTC_TIMESTAMP() + INTERVAL 20 MINUTE");
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", STAFF_ID, CUSTOMER_ID);
    }

    @Test
    void exactlyOnePhoneGetsIn() throws Exception {
        String payload = qr.sign(ticketId, java.time.Instant.now().plusSeconds(7200));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        checkIn.checkIn(STAFF_EMAIL, new CheckInRequest(payload, null));
                        return "OK";
                    } catch (BusinessException e) {
                        return e.getCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) outcomes.add(f.get(60, TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter("OK"::equals).count(), outcomes.toString());
            assertEquals(THREADS - 1, outcomes.stream().filter("ALREADY_CHECKED_IN"::equals).count(), outcomes.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(STAFF_ID, jdbc.queryForObject("SELECT checked_in_by FROM tickets WHERE id=?", Long.class, ticketId));
    }
}
