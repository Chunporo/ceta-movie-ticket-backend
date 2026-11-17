package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.service.inventory.InventoryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(showSql = false)
@Import({BookingExpiryServiceImpl.class, BookingCreationServiceImpl.class, com.movie.ticket_booking.service.BookingExtrasCalculator.class, InventoryServiceImpl.class, PricingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingExpiryServiceTest {
    @Autowired private BookingExpiryService expiry;
    @Autowired private BookingCreationService creation;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private final String[] emails = new String[3];
    private final long[] userIds = new long[3];
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            for (int i = 0; i < 3; i++) {
                emails[i] = i + suffix + "@example.invalid";
                userIds[i] = em.persist(User.builder().username(i + suffix).email(emails[i]).passwordHash("x").build()).getId();
            }
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Expiry test").totalRows(1).totalColumns(4).build());
            Movie movie = em.persist(Movie.builder().title("Expiry test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            seatIds = new java.util.ArrayList<>();
            for (int col = 1; col <= 4; col++) {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).build());
                em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId())).showtime(showtime).seat(seat).build());
                seatIds.add(seat.getId());
            }
            em.flush();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
    }

    @AfterEach
    void clean() {
        String bookingIds = "SELECT id FROM booking WHERE showtime_id=" + showtimeId;
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            for (long id : userIds) em.remove(em.find(User.class, id));
        });
    }

    @Test
    void overdueBookingExpiresItsSeatsAreFreedAndOthersAreUntouched() {
        long overdue = creation.create(emails[0], new CreateBookingRequest(showtimeId, seatIds.subList(0, 2))).bookingId();
        long live = creation.create(emails[1], new CreateBookingRequest(showtimeId, seatIds.subList(2, 3))).bookingId();
        long paid = creation.create(emails[2], new CreateBookingRequest(showtimeId, seatIds.subList(3, 4))).bookingId();
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(30));
        jdbc.update("UPDATE booking SET hold_expires_at=? WHERE id IN (?,?)", past, overdue, paid);
        jdbc.update("UPDATE showtime_seat SET held_until=? WHERE booking_id=?", past, overdue);
        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", paid);
        jdbc.update("UPDATE showtime_seat SET status='SOLD',held_until=NULL,booking_id=NULL WHERE booking_id=?", paid);

        BookingExpiryService.SweepResult first = expiry.expireOverdue(Instant.now());
        BookingExpiryService.SweepResult second = expiry.expireOverdue(Instant.now());

        assertEquals(new BookingExpiryService.SweepResult(1, 2), first);
        assertEquals(new BookingExpiryService.SweepResult(0, 0), second, "second run is a harmless no-op");
        assertEquals("EXPIRED", status(overdue));
        assertEquals("PENDING", status(live));
        assertEquals("PAID", status(paid));
        assertEquals(1, count("SELECT COUNT(*) FROM booking_status_history WHERE to_status='EXPIRED' AND from_status='PENDING' AND booking_id=" + overdue));
        assertEquals(0, count("SELECT COUNT(*) FROM booking_status_history WHERE booking_id IN (" + live + "," + paid + ")"));
        assertEquals(2, count("SELECT COUNT(*) FROM showtime_seat WHERE status='AVAILABLE' AND booking_id IS NULL AND held_until IS NULL AND showtime_id=" + showtimeId));
        assertEquals(1, count("SELECT COUNT(*) FROM showtime_seat WHERE status='HELD' AND booking_id=" + live));
        assertEquals(1, count("SELECT COUNT(*) FROM showtime_seat WHERE status='SOLD' AND showtime_id=" + showtimeId));
    }

    @Test
    void emptySweepIsFine() {
        assertEquals(new BookingExpiryService.SweepResult(0, 0), expiry.expireOverdue(Instant.now()));
    }

    private String status(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
