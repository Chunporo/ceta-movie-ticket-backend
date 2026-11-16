package com.movie.ticket_booking.service.inventory;

import com.movie.ticket_booking.exception.SeatConflictException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** T3 / Gate A: 50 threads race for one seat on real MySQL; exactly one may win. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class ConcurrentClaimTest {
    private static final int THREADS = 50;
    private static final long FIRST_USER_ID = 91_000L;

    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;

    private long showtimeId;
    private long targetSeatId;
    private List<Long> bookingIds;

    @BeforeEach
    void seed() {
        cleanUp();
        // V1 seeds one showtime with 120 AVAILABLE seats (A1-H15).
        showtimeId = jdbc.queryForObject("SELECT MIN(id) FROM showtime", Long.class);
        targetSeatId = jdbc.queryForObject("""
                SELECT s.id FROM seat s JOIN showtime sh ON sh.room_id = s.room_id
                WHERE sh.id=? AND s.label='F7'""", Long.class, showtimeId);
        bookingIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long userId = FIRST_USER_ID + i;
            jdbc.update("""
                    INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at)
                    VALUES (?,?,?,'test-only',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())""",
                    userId, "concurrent-" + i, "concurrent-" + i + "@example.invalid");
            jdbc.update("""
                    INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at)
                    VALUES (?,?,'PENDING',100000,UTC_TIMESTAMP() + INTERVAL 10 MINUTE)""",
                    userId, showtimeId);
            bookingIds.add(jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, userId));
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL "
                + "WHERE booking_id IN (SELECT id FROM booking WHERE user_id >= ? AND user_id < ?)",
                FIRST_USER_ID, FIRST_USER_ID + THREADS);
        jdbc.update("DELETE FROM booking WHERE user_id >= ? AND user_id < ?", FIRST_USER_ID, FIRST_USER_ID + THREADS);
        jdbc.update("DELETE FROM users WHERE id >= ? AND id < ?", FIRST_USER_ID, FIRST_USER_ID + THREADS);
    }

    @Test
    void exactlyOneOfFiftyConcurrentClaimsWins() throws Exception {
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE showtime_id=?", showtimeId);

        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        Instant expiry = Instant.now().plusSeconds(600);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (Long bookingId : bookingIds) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        inventory.claim(showtimeId, List.of(targetSeatId), bookingId, expiry);
                        winners.incrementAndGet();
                    } catch (SeatConflictException e) {
                        conflicts.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "all threads must be ready");
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "all threads must finish");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(unexpected.isEmpty(), "only SeatConflictException allowed, got: " + unexpected);
        assertEquals(1, winners.get(), "exactly one claim must succeed");
        assertEquals(THREADS - 1, conflicts.get(), "the rest must see SeatConflictException");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='HELD'", Integer.class, showtimeId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND seat_id=? AND status='HELD' AND booking_id IS NOT NULL",
                Integer.class, showtimeId, targetSeatId));
    }
}
