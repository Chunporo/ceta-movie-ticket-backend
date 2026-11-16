package com.movie.ticket_booking.service;

import com.movie.ticket_booking.entity.enums.RefundReason;
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

/** Ten threads enqueue a refund for one booking on real MySQL: one row, no errors. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class ConcurrentRefundEnqueueTest {
    private static final long USER_ID = 93_000L;
    private static final int THREADS = 10;

    @Autowired private RefundService refunds;
    @Autowired private JdbcTemplate jdbc;
    private long bookingId;

    @BeforeEach
    void seed() {
        cleanUp();
        long showtimeId = jdbc.queryForObject("SELECT MIN(id) FROM showtime", Long.class);
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                USER_ID, "refund-test", "refund-test@example.invalid");
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'EXPIRED',100000,UTC_TIMESTAMP())", USER_ID, showtimeId);
        bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, USER_ID);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM refund_queue WHERE booking_id IN (SELECT id FROM booking WHERE user_id=?)", USER_ID);
        jdbc.update("DELETE FROM booking WHERE user_id=?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id=?", USER_ID);
    }

    @Test
    void parallelEnqueuesLeaveExactlyOneRow() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    refunds.enqueue(bookingId, RefundReason.LATE_PAYMENT, 100000L, "race");
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, bookingId));
    }
}
