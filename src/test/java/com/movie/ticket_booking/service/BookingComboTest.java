package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.BookingDetailResponse;
import com.movie.ticket_booking.dto.booking.ComboLineRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.BusinessException;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** B3: snacks join the booking total, are frozen with it, and never reach the tickets. */
@DataJpaTest(showSql = false)
@Import({BookingServiceImpl.class, BookingLifecycleServiceImpl.class, BookingCreationServiceImpl.class, com.movie.ticket_booking.service.BookingExtrasCalculator.class, InventoryServiceImpl.class, PricingService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingComboTest {
    @Autowired private BookingCreationService creation;
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String email;
    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private List<Long> seats;
    private long soloId;
    private long coupleId;
    private long retiredId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            email = suffix + "@example.invalid";
            User user = em.persist(User.builder().username(suffix).email(email).passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Combo test").totalRows(1).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Combo test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
            for (int col = 1; col <= 2; col++) {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).build());
                em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId())).showtime(showtime).seat(seat).build());
                ids.add(seat.getId());
            }
            soloId = em.persist(Combo.builder().code("CT-" + suffix.substring(0, 8)).name("Combo Solo").price(79_000L).build()).getId();
            coupleId = em.persist(Combo.builder().code("CT-" + suffix.substring(9, 17)).name("Combo Doi").price(119_000L).build()).getId();
            retiredId = em.persist(Combo.builder().code("CT-" + suffix.substring(19, 27)).name("Old").price(1_000L).active(false).build()).getId();
            em.flush();
            seats = ids;
            userId = user.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
    }

    @AfterEach
    void clean() {
        String bookings = "SELECT id FROM booking WHERE showtime_id=" + showtimeId;
        jdbc.update("DELETE FROM booking_combo WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + bookings + ")");
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        jdbc.update("DELETE FROM combo WHERE id IN (?,?,?)", soloId, coupleId, retiredId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
        });
    }

    private CreateBookingRequest request(ComboLineRequest... lines) {
        return new CreateBookingRequest(showtimeId, seats, List.of(lines));
    }

    private long countBookings() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE showtime_id=?", Long.class, showtimeId);
    }

    @Test
    void theTotalIsSeatsPlusCombosAndEachPartIsStored() {
        CreateBookingResponse r = creation.create(email, request(new ComboLineRequest(soloId, 2), new ComboLineRequest(coupleId, 1)));

        long seatsSum = r.seats().stream().mapToLong(CreateBookingResponse.Seat::price).sum();
        assertEquals(2 * 79_000L + 119_000L, r.comboAmount());
        assertEquals(seatsSum + r.comboAmount(), r.totalAmount());
        assertEquals(r.totalAmount(), jdbc.queryForObject("SELECT total_amount FROM booking WHERE id=?", Long.class, r.bookingId()));
        assertEquals(r.comboAmount(), jdbc.queryForObject("SELECT combo_amount FROM booking WHERE id=?", Long.class, r.bookingId()));
        assertEquals(r.comboAmount(), jdbc.queryForObject("SELECT SUM(quantity*unit_price) FROM booking_combo WHERE booking_id=?", Long.class, r.bookingId()));
        assertEquals(2, r.combos().size());
    }

    @Test
    void noCombosMeansAnUnchangedBooking() {
        CreateBookingResponse r = creation.create(email, new CreateBookingRequest(showtimeId, seats));
        assertEquals(0L, r.comboAmount());
        assertTrue(r.combos().isEmpty());
        assertEquals(r.seats().stream().mapToLong(CreateBookingResponse.Seat::price).sum(), r.totalAmount());
    }

    @Test
    void aLaterPriceChangeOrRenameNeverTouchesTheBooking() {
        CreateBookingResponse r = creation.create(email, request(new ComboLineRequest(soloId, 1)));

        jdbc.update("UPDATE combo SET price=999000, name='Renamed' WHERE id=?", soloId);

        BookingDetailResponse detail = lifecycle.get(email, r.bookingId());
        assertEquals(r.totalAmount(), detail.totalAmount());
        assertEquals(79_000L, detail.comboAmount());
        assertEquals("Combo Solo", detail.combos().get(0).name());
        assertEquals(79_000L, detail.combos().get(0).unitPrice());
    }

    @Test
    void anInactiveOrUnknownComboIsRefusedAndNothingIsHeld() {
        for (long bad : new long[]{retiredId, 987_654_321L}) {
            BusinessException e = assertThrows(BusinessException.class, () -> creation.create(email, request(new ComboLineRequest(bad, 1))));
            assertEquals("COMBO_UNAVAILABLE", e.getCode());
        }
        assertEquals(0, countBookings());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='HELD'", Long.class, showtimeId));
    }

    @Test
    void badQuantitiesAndDuplicateLinesAreRefused() {
        List<ComboLineRequest[]> bad = List.of(
                new ComboLineRequest[]{new ComboLineRequest(soloId, 0)},
                new ComboLineRequest[]{new ComboLineRequest(soloId, -1)},
                new ComboLineRequest[]{new ComboLineRequest(soloId, 11)},
                new ComboLineRequest[]{new ComboLineRequest(soloId, null)},
                new ComboLineRequest[]{new ComboLineRequest(null, 1)},
                new ComboLineRequest[]{new ComboLineRequest(soloId, 1), new ComboLineRequest(soloId, 2)});
        for (ComboLineRequest[] lines : bad) {
            BusinessException e = assertThrows(BusinessException.class, () -> creation.create(email, request(lines)));
            assertEquals("INVALID_REQUEST", e.getCode());
        }
        assertEquals(0, countBookings());
    }
}
