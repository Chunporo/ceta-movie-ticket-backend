package com.movie.ticket_booking.service.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Six parallel create-url calls for one booking on real MySQL: six distinct attempt numbers, no errors. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class ConcurrentPaymentUrlTest {
    private static final long USER_ID = 95_000L;
    private static final String EMAIL = "pay-url-test@example.invalid";
    private static final int THREADS = 6;

    @Autowired private PaymentUrlService urls;
    @Autowired private JdbcTemplate jdbc;
    private long bookingId;

    @BeforeEach
    void seed() {
        cleanUp();
        long showtimeId = jdbc.queryForObject("SELECT MIN(id) FROM showtime", Long.class);
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                USER_ID, "pay-url-test", EMAIL);
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PENDING',200000,UTC_TIMESTAMP() + INTERVAL 10 MINUTE)", USER_ID, showtimeId);
        bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, USER_ID);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id IN (SELECT id FROM booking WHERE user_id=?)", USER_ID);
        jdbc.update("DELETE FROM booking WHERE user_id=?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id=?", USER_ID);
    }

    @Test
    void parallelCallsGetDistinctAttemptNumbers() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> futures = new ArrayList<>();
        Set<String> refs = new TreeSet<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return urls.create(EMAIL, bookingId, "127.0.0.1").txnRef();
                }));
            }
            start.countDown();
            for (Future<String> f : futures) refs.add(f.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(THREADS, refs.size(), refs.toString());
        assertEquals(THREADS, jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE booking_id=?", Integer.class, bookingId));
    }
}
