package com.movie.ticket_booking.service.inventory;

import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.SeatConflictException;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** H2 verifies state transitions and rollback; MySQL locking is the T3 gate. */
@DataJpaTest(showSql = false)
@Import(InventoryServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryServiceTest {
    @Autowired private InventoryService inventory;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    private Fixture fixture;

    @BeforeEach
    void seed() {
        fixture = new TransactionTemplate(transactions).execute(status -> {
            String suffix = UUID.randomUUID().toString();
            User user = em.persist(User.builder().username(suffix).email(suffix + "@example.invalid")
                    .passwordHash("test-only").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Inventory test")
                    .totalRows(1).totalColumns(3).build());
            Movie movie = em.persist(Movie.builder().title("Inventory test").slug(suffix)
                    .posterUrl("https://example.invalid/poster").ageRating(AgeRating.P)
                    .status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant tomorrow = Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(tomorrow)
                    .endTime(tomorrow.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Instant expiry = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
            Booking booking = em.persist(Booking.builder().user(user).showtime(showtime)
                    .totalAmount(200000L).holdExpiresAt(expiry).build());
            List<Long> seats = java.util.stream.IntStream.rangeClosed(1, 3).mapToObj(column -> {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + column)
                        .rowNumber(1).columnNumber(column).build());
                em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId()))
                        .showtime(showtime).seat(seat).build());
                return seat.getId();
            }).toList();
            em.flush();
            return new Fixture(showtime.getId(), booking.getId(), seats, expiry,
                    user.getId(), room.getId(), movie.getId());
        });
    }

    @AfterEach
    void clean() {
        if (fixture == null) return;
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", fixture.showtimeId());
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", fixture.showtimeId());
        jdbc.update("DELETE FROM showtime WHERE id=?", fixture.showtimeId());
        jdbc.update("DELETE FROM seat WHERE room_id=?", fixture.roomId());
        jdbc.update("DELETE FROM rooms WHERE id=?", fixture.roomId());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, fixture.movieId()));
            em.remove(em.find(User.class, fixture.userId()));
        });
    }

    @Test
    void claimAllAvailableSeats() {
        assertEquals(List.of(), inventory.claim(fixture.showtimeId(), selected(), fixture.bookingId(), fixture.expiry()));
        assertEquals(2, count("status='HELD' AND booking_id=" + fixture.bookingId()));
        assertEquals(1, count("status='AVAILABLE'"));
        assertEquals(fixture.expiry(), jdbc.queryForObject(
                "SELECT held_until FROM showtime_seat WHERE showtime_id=? AND seat_id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), fixture.showtimeId(), selected().getFirst()));
    }

    @Test
    void claimReportsUnavailableAndMissingSeatsWithoutChangingAvailableOnes() {
        jdbc.update("UPDATE showtime_seat SET status='BLOCKED' WHERE showtime_id=? AND seat_id=?",
                fixture.showtimeId(), selected().getLast());
        SeatConflictException conflict = assertThrows(SeatConflictException.class,
                () -> inventory.claim(fixture.showtimeId(), List.of(selected().getFirst(), selected().getLast(), -1L),
                        fixture.bookingId(), fixture.expiry()));
        assertEquals(List.of(selected().getLast(), -1L), conflict.getConflictSeatIds());
        assertEquals(2, count("status='AVAILABLE'"));
        assertEquals(0, count("status='HELD'"));
    }

    @Test
    void releaseTwiceClearsOnlyThisBookingsHolds() {
        claim();
        assertEquals(2, count("status='HELD'"));
        jdbc.update("UPDATE showtime_seat SET status='SOLD' WHERE showtime_id=? AND seat_id=?",
                fixture.showtimeId(), fixture.seats().getLast());
        inventory.release(fixture.bookingId());
        inventory.release(fixture.bookingId());
        assertEquals(2, count("status='AVAILABLE' AND booking_id IS NULL AND held_until IS NULL"));
        assertEquals(1, count("status='SOLD'"));
    }

    @Test
    void promotionClearsHoldMetadataAndCannotBeReleased() {
        claim();
        inventory.promoteToSold(fixture.bookingId());
        inventory.release(fixture.bookingId());
        assertEquals(2, count("status='SOLD' AND booking_id IS NULL AND held_until IS NULL"));
        assertEquals(1, count("status='AVAILABLE'"));
    }

    @Test
    void partialPromotionOfExpiredAndValidHoldsRollsBack() {
        claim();
        jdbc.update("UPDATE showtime_seat SET held_until=? WHERE showtime_id=? AND seat_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), fixture.showtimeId(), selected().getLast());
        assertThrows(SeatConflictException.class, () -> inventory.promoteToSold(fixture.bookingId()));
        assertEquals(2, count("status='HELD' AND booking_id=" + fixture.bookingId()));
        assertEquals(0, count("status='SOLD'"));
    }

    @Test
    void promotionWithoutHoldsFails() {
        assertThrows(SeatConflictException.class, () -> inventory.promoteToSold(fixture.bookingId()));
        assertEquals(3, count("status='AVAILABLE'"));
    }

    @Test
    void sweeperReleasesOnlyExpiredHoldsAndReturnsCount() {
        claim();
        jdbc.update("UPDATE showtime_seat SET held_until=? WHERE showtime_id=? AND seat_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), fixture.showtimeId(), selected().getFirst());
        assertEquals(1, inventory.releaseExpired());
        assertEquals(1, count("status='HELD' AND booking_id=" + fixture.bookingId()));
        assertEquals(2, count("status='AVAILABLE' AND held_until IS NULL AND booking_id IS NULL"));
        assertEquals(0, inventory.releaseExpired());
    }

    @Test
    void missingShowtimeCannotClaimAnything() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> inventory.claim(-1L, selected(), fixture.bookingId(), fixture.expiry()));
        assertEquals(404, error.getStatusCode().value());
        assertEquals(3, count("status='AVAILABLE'"));
    }

    @Test
    void invalidSeatSelectionsAndExpiredDeadlineCannotClaim() {
        assertThrows(ResponseStatusException.class,
                () -> inventory.claim(fixture.showtimeId(), List.of(), fixture.bookingId(), fixture.expiry()));
        assertThrows(ResponseStatusException.class,
                () -> inventory.claim(fixture.showtimeId(), List.of(selected().getFirst(), selected().getFirst()), fixture.bookingId(), fixture.expiry()));
        assertThrows(ResponseStatusException.class,
                () -> inventory.claim(fixture.showtimeId(), selected(), fixture.bookingId(), Instant.now().minusSeconds(60)));
        assertEquals(3, count("status='AVAILABLE'"));
    }

    private void claim() {
        inventory.claim(fixture.showtimeId(), selected(), fixture.bookingId(), fixture.expiry());
    }

    private List<Long> selected() {
        return fixture.seats().subList(0, 2);
    }

    private int count(String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND " + condition,
                Integer.class, fixture.showtimeId());
    }

    record Fixture(Long showtimeId, Long bookingId, List<Long> seats, Instant expiry,
                   Long userId, Long roomId, UUID movieId) {}
}
