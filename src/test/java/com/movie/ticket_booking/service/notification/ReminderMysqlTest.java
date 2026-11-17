package com.movie.ticket_booking.service.notification;

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

/** Real MySQL: the set-based reminder INSERT is valid SQL and two overlapping runs still send one reminder per booking. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class ReminderMysqlTest {
    private static final long FIRST_USER = 99_800L;
    private static final int USERS = 3;

    @Autowired private ShowtimeReminderJob job;
    @Autowired private NotificationService notifications;
    @Autowired private JdbcTemplate jdbc;
    private long showtimeId;

    @BeforeEach
    void seed() {
        cleanUp();
        for (int i = 0; i < USERS; i++) {
            jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                    FIRST_USER + i, "rem-" + i, "rem-" + i + "@example.invalid");
        }
        jdbc.update("""
                INSERT INTO showtime (movie_id,room_id,start_time,end_time,base_price,created_at,updated_at,status)
                SELECT movie_id,1,UTC_TIMESTAMP() + INTERVAL 90 MINUTE,UTC_TIMESTAMP() + INTERVAL 210 MINUTE,100000,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'SCHEDULED'
                FROM showtime WHERE id=1""");
        showtimeId = jdbc.queryForObject("SELECT MAX(id) FROM showtime", Long.class);
        for (int i = 0; i < USERS; i++) {
            jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,?,100000,UTC_TIMESTAMP())",
                    FIRST_USER + i, showtimeId, i == USERS - 1 ? "PENDING" : "PAID");
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notification WHERE user_id BETWEEN ? AND ?", FIRST_USER, FIRST_USER + 10);
        jdbc.update("DELETE FROM booking WHERE user_id BETWEEN ? AND ?", FIRST_USER, FIRST_USER + 10);
        jdbc.update("DELETE FROM showtime WHERE id > 1 AND base_price=100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 60 MINUTE AND start_time < UTC_TIMESTAMP() + INTERVAL 120 MINUTE AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.showtime_id = showtime.id)");
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", FIRST_USER, FIRST_USER + 10);
    }

    private int reminders() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE type='SHOWTIME_REMINDER' AND user_id BETWEEN ? AND ?", Integer.class, FIRST_USER, FIRST_USER + 10);
    }

    @Test
    void overlappingRunsSendOneReminderPerPaidBooking() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    try {
                        return job.remind(Instant.now());
                    } catch (org.springframework.dao.DataIntegrityViolationException | org.springframework.dao.CannotAcquireLockException e) {
                        return 0; // lost the race on the unique key: someone else's insert is the one that counts
                    }
                }));
            }
            start.countDown();
            for (Future<Integer> f : runs) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(USERS - 1, reminders(), "the PENDING booking gets none");
        assertEquals(0, job.remind(Instant.now()));
    }

    @Test
    void duplicateEventsOnTheSameBookingCreateOneRow() throws Exception {
        long bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, FIRST_USER);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<Boolean>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    try {
                        return notifications.notify(FIRST_USER, com.movie.ticket_booking.entity.enums.NotificationType.ORDER_PAID, bookingId);
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            for (Future<Boolean> f : calls) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND type='ORDER_PAID'", Integer.class, FIRST_USER));
    }
}
