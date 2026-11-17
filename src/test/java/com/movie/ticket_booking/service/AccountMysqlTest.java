package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.auth.DeleteAccountRequest;
import com.movie.ticket_booking.dto.auth.LoginRequest;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.inventory.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL: the login-lockout UPDATE counts exactly (SET order matters there) and account deletion leaves no hold behind. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class AccountMysqlTest {
    private static final long USER = 99_500L;
    private static final String EMAIL = "acct-mysql@example.invalid";
    private static final String PASSWORD = "correct-horse-1";

    @Autowired private AuthService auth;
    @Autowired private InventoryService inventory;
    @Autowired private PasswordEncoder encoder;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    private long showtimeId;

    @BeforeEach
    void seed() {
        cleanUp();
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,?,1,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                USER, "acct-mysql", EMAIL, encoder.encode(PASSWORD));
        jdbc.update("""
                INSERT INTO showtime (movie_id,room_id,start_time,end_time,base_price,created_at,updated_at,status)
                SELECT movie_id,1,UTC_TIMESTAMP() + INTERVAL 6 DAY,UTC_TIMESTAMP() + INTERVAL 6 DAY + INTERVAL 2 HOUR,100000,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'SCHEDULED'
                FROM showtime WHERE id=1""");
        showtimeId = jdbc.queryForObject("SELECT MAX(id) FROM showtime", Long.class);
        inventory.initializeShowtime(showtimeId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id=?", USER);
        jdbc.update("DELETE FROM password_reset_token WHERE user_id=?", USER);
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (SELECT id FROM booking WHERE user_id=?)", USER);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id IN (SELECT showtime_id FROM booking WHERE user_id=?)", USER);
        jdbc.update("DELETE FROM booking WHERE user_id=?", USER);
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id > 1 AND showtime_id IN (SELECT id FROM showtime WHERE id > 1 AND base_price=100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 5 DAY AND start_time < UTC_TIMESTAMP() + INTERVAL 7 DAY AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.showtime_id = showtime.id))");
        jdbc.update("DELETE FROM showtime WHERE id > 1 AND base_price=100000 AND start_time > UTC_TIMESTAMP() + INTERVAL 5 DAY AND start_time < UTC_TIMESTAMP() + INTERVAL 7 DAY AND NOT EXISTS (SELECT 1 FROM booking b WHERE b.showtime_id = showtime.id)");
        jdbc.update("DELETE FROM users WHERE id=?", USER);
    }

    private String attempt(String password) {
        try {
            auth.login(new LoginRequest(EMAIL, password));
            return "OK";
        } catch (BusinessException e) {
            return e.getCode();
        } catch (ResponseStatusException e) {
            return String.valueOf(e.getStatusCode().value());
        }
    }

    @Test
    void exactlyTheFifthConsecutiveFailureLocksAndTheCounterRestarts() {
        for (int i = 1; i <= 4; i++) assertEquals("401", attempt("wrong"), "attempt " + i);
        assertEquals(4, jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE id=?", Integer.class, USER));
        assertNull(jdbc.queryForObject("SELECT locked_until FROM users WHERE id=?", java.sql.Timestamp.class, USER));

        assertEquals("ACCOUNT_LOCKED", attempt("wrong"));
        assertEquals(0, jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE id=?", Integer.class, USER));
        assertNotNull(jdbc.queryForObject("SELECT locked_until FROM users WHERE id=?", java.sql.Timestamp.class, USER));
        assertEquals("ACCOUNT_LOCKED", attempt(PASSWORD));

        jdbc.update("UPDATE users SET locked_until = UTC_TIMESTAMP() - INTERVAL 1 SECOND WHERE id=?", USER);
        assertEquals("OK", attempt(PASSWORD));
        assertEquals(0, jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE id=?", Integer.class, USER));
        assertNull(jdbc.queryForObject("SELECT locked_until FROM users WHERE id=?", java.sql.Timestamp.class, USER));
    }

    @Test
    void twelveParallelWrongPasswordsNeverBreakAndEndLocked() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<String>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return attempt("wrong");
                }));
            }
            start.countDown();
            for (Future<String> f : calls) {
                String outcome = f.get(60, TimeUnit.SECONDS);
                assertTrue(outcome.equals("401") || outcome.equals("ACCOUNT_LOCKED"), outcome);
            }
        } finally {
            pool.shutdownNow();
        }
        assertNotNull(jdbc.queryForObject("SELECT locked_until FROM users WHERE id=?", java.sql.Timestamp.class, USER));
        assertEquals("ACCOUNT_LOCKED", attempt(PASSWORD));
    }

    @Test
    void deletingTheAccountCancelsPendingBookingsAndReleasesTheirSeats() {
        long seatId = jdbc.queryForObject("SELECT MIN(id) FROM seat WHERE room_id=1 AND active=1", Long.class);
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PENDING',100000,UTC_TIMESTAMP() + INTERVAL 10 MINUTE)", USER, showtimeId);
        long bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, USER);
        new TransactionTemplate(transactions).executeWithoutResult(s ->
                inventory.claim(showtimeId, List.of(seatId), bookingId, Instant.now().plusSeconds(600)));

        auth.deleteAccount(EMAIL, new DeleteAccountRequest(PASSWORD));

        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM showtime_seat WHERE showtime_id=? AND seat_id=?", String.class, showtimeId, seatId));
        assertEquals("deleted-" + USER + "@deleted.invalid", jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, USER));
        assertEquals("401", attempt(PASSWORD));
    }

    @Test
    void anUpcomingPaidTicketBlocksDeletionAndChangesNothing() {
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PAID',100000,UTC_TIMESTAMP())", USER, showtimeId);

        BusinessException ex = assertThrows(BusinessException.class, () -> auth.deleteAccount(EMAIL, new DeleteAccountRequest(PASSWORD)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("HAS_ACTIVE_TICKETS", ex.getCode());
        assertEquals(EMAIL, jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, USER));
        assertEquals("OK", attempt(PASSWORD));
    }
}
