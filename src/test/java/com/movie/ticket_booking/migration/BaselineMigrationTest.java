package com.movie.ticket_booking.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/** Uses an isolated MySQL database; ordinary unit tests need no external services. */
@EnabledIfEnvironmentVariable(named = "T1_MYSQL_URL", matches = ".+")
class BaselineMigrationTest {
    @Test
    void migrationSeedsInventoryAndEnforcesBookingAndSeatConstraints() throws Exception {
        String url = System.getenv("T1_MYSQL_URL");
        String user = System.getenv("T1_MYSQL_USER");
        String password = System.getenv("T1_MYSQL_PASSWORD");
        Flyway flyway = Flyway.configure().dataSource(url, user, password).load();
        flyway.migrate();
        assertNotNull(flyway.info().current(), "V1 to V3 must apply on an empty database");
        assertEquals("6", flyway.info().current().getVersion().toString());
        assertEquals(0, flyway.migrate().migrationsExecuted, "migration is repeatable without reseeding");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement sql = connection.createStatement()) {
            assertEquals(120, scalar(sql, "SELECT COUNT(*) FROM seat WHERE room_id=1"));
            assertEquals(120, scalar(sql, "SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=1 AND status='AVAILABLE'"));
            assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM showtime WHERE id=1 AND DATE(start_time)=DATE(UTC_TIMESTAMP() + INTERVAL 1 DAY)"));
            connection.setAutoCommit(false);
            try {
                sql.executeUpdate("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (90001,'migration-test','migration-test@example.invalid','test-only',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
                String booking = "INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (90001,1,'PENDING',100000,UTC_TIMESTAMP() + INTERVAL 10 MINUTE)";
                sql.executeUpdate(booking);
                long bookingId = scalar(sql, "SELECT id FROM booking WHERE user_id=90001");
                assertSqlState("23000", () -> sql.executeUpdate(booking));
                assertSqlState("HY000", () -> sql.executeUpdate("UPDATE showtime_seat SET status='HELD' WHERE showtime_id=1 AND seat_id=1"));
                sql.executeUpdate("UPDATE showtime_seat SET status='HELD',booking_id=" + bookingId + ",held_until=UTC_TIMESTAMP() + INTERVAL 10 MINUTE WHERE showtime_id=1 AND seat_id=1");
                assertEquals(1, scalar(sql, "SELECT COUNT(*) FROM showtime_seat WHERE status='HELD'"));
                assertSqlState("23000", () -> sql.executeUpdate("INSERT INTO showtime_seat (showtime_id,seat_id) VALUES (999999,1)"));
                sql.executeUpdate("INSERT INTO tickets (booking_id,showtime_id,seat_id,price,code,created_at,updated_at) VALUES (" + bookingId + ",1,1,100000,'migration-test',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
                assertSqlState("23000", () -> sql.executeUpdate("INSERT INTO tickets (booking_id,showtime_id,seat_id,price,code,created_at,updated_at) VALUES (" + bookingId + ",1,1,100000,'migration-duplicate',UTC_TIMESTAMP(),UTC_TIMESTAMP())"));
                sql.executeUpdate("UPDATE booking SET status='CANCELLED' WHERE id=" + bookingId);
                assertEquals(1, sql.executeUpdate(booking), "terminal booking releases the unique active key");
            } finally {
                connection.rollback();
            }
        }
    }

    private long scalar(Statement sql, String query) throws SQLException {
        try (var rows = sql.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void assertSqlState(String expected, SqlAction action) {
        assertEquals(expected, assertThrows(SQLException.class, action::run).getSQLState());
    }

    @FunctionalInterface
    interface SqlAction {
        void run() throws SQLException;
    }
}
