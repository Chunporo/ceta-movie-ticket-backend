package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.ShowtimeSeatStatus;
import com.movie.ticket_booking.dto.booking.ShowtimeSeatStatusResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.service.inventory.InventoryService;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F1: the seat map shows HELD and SOLD from inventory, to everybody, without any hold timing. */
@DataJpaTest(showSql = false)
@Import({BookingServiceImpl.class, BookingCreationServiceImpl.class, com.movie.ticket_booking.service.BookingExtrasCalculator.class, InventoryServiceImpl.class, PricingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatMapTest {
    @Autowired private BookingService bookingService;
    @Autowired private BookingCreationService creation;
    @Autowired private InventoryService inventory;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String email;
    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            email = suffix + "@example.invalid";
            userId = em.persist(User.builder().username(suffix).email(email).passwordHash("x").build()).getId();
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Seat map test").totalRows(1).totalColumns(5).build());
            Movie movie = em.persist(Movie.builder().title("Seat map test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            seatIds = new java.util.ArrayList<>();
            for (int col = 1; col <= 5; col++) {
                seatIds.add(em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col)
                        .active(col != 5).build()).getId());
            }
            em.flush();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
        inventory.initializeShowtime(showtimeId);
    }

    @AfterEach
    void clean() {
        String bookingIds = "SELECT id FROM booking WHERE showtime_id=" + showtimeId;
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
        });
    }

    private Map<String, ShowtimeSeatStatus> map(String requester) {
        return bookingService.getShowtimeSeats(showtimeId, requester).stream()
                .collect(Collectors.toMap(ShowtimeSeatStatusResponse::getLabel, ShowtimeSeatStatusResponse::getStatus));
    }

    @Test
    void heldSoldAvailableAndInactiveSeatsAreAllShownToEveryone() {
        creation.create(email, new CreateBookingRequest(showtimeId, seatIds.subList(0, 2)));          // A1, A2 held
        jdbc.update("UPDATE showtime_seat SET status='SOLD',held_until=NULL,booking_id=NULL WHERE showtime_id=? AND seat_id=?",
                showtimeId, seatIds.get(2));                                                                // A3 sold

        Map<String, ShowtimeSeatStatus> expected = Map.of("A1", ShowtimeSeatStatus.HELD, "A2", ShowtimeSeatStatus.HELD,
                "A3", ShowtimeSeatStatus.SOLD, "A4", ShowtimeSeatStatus.AVAILABLE, "A5", ShowtimeSeatStatus.INACTIVE);
        assertEquals(expected, map(null), "anonymous visitor");
        assertEquals(expected, map("someone-else@example.invalid"), "another user sees the same");
        assertEquals(expected, map(email), "the holder sees the same: HELD, nothing identifying");
    }

    @Test
    void noHoldTimingLeaksAndSeatsFreeUpAgainAfterRelease() {
        long bookingId = creation.create(email, new CreateBookingRequest(showtimeId, seatIds.subList(0, 1))).bookingId();

        assertTrue(bookingService.getShowtimeSeats(showtimeId, null).stream().allMatch(s -> s.getHoldExpiresAt() == null));

        inventory.release(bookingId);
        assertEquals(ShowtimeSeatStatus.AVAILABLE, map(null).get("A1"));
    }

    @Test
    void aSeatWithoutAnInventoryRowIsNotOffered() {
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=? AND seat_id=?", showtimeId, seatIds.get(3));

        assertEquals(ShowtimeSeatStatus.INACTIVE, map(null).get("A4"));
    }

    @Test
    void seatCountsFollowSoldAndBlockedSeats() {
        var fresh = inventory.seatCounts(List.of(showtimeId, -1L)).get(showtimeId);
        assertNotNull(fresh);
        assertTrue(fresh.total() > 0);
        assertEquals(fresh.total(), fresh.available());
        assertNull(inventory.seatCounts(List.of(-1L)).get(-1L), "a showtime without inventory is absent, not zero");

        jdbc.update("UPDATE showtime_seat SET status='SOLD' WHERE showtime_id=? AND seat_id=?", showtimeId, seatIds.get(0));
        var afterSale = inventory.seatCounts(List.of(showtimeId)).get(showtimeId);
        assertEquals(fresh.available() - 1, afterSale.available());
        assertEquals(fresh.total(), afterSale.total());

        jdbc.update("UPDATE showtime_seat SET status='BLOCKED' WHERE showtime_id=? AND seat_id=?", showtimeId, seatIds.get(1));
        var afterBlock = inventory.seatCounts(List.of(showtimeId)).get(showtimeId);
        assertEquals(afterSale.available() - 1, afterBlock.available());
        assertEquals(fresh.total() - 1, afterBlock.total(), "blocked seats cannot be sold, so they are not counted");
        assertTrue(inventory.seatCounts(List.of()).isEmpty());
    }
}
