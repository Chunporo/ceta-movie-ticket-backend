package com.movie.ticket_booking.service.inventory;

import com.movie.ticket_booking.dto.booking.SeatStatusBroadcast;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.event.SeatStatusChangedEvent;
import com.movie.ticket_booking.exception.SeatConflictException;
import com.movie.ticket_booking.listener.SeatBroadcastListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Every inventory change emits an event; the WebSocket message goes out only after the commit. */
@DataJpaTest(showSql = false)
@Import({InventoryServiceImpl.class, SeatBroadcastListener.class})
@RecordApplicationEvents
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryBroadcastTest {
    @Autowired private InventoryService inventory;
    @Autowired private ApplicationEvents events;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private SimpMessagingTemplate messaging;

    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private long bookingId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            User user = em.persist(User.builder().username(suffix).email(suffix + "@example.invalid").passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Broadcast test").totalRows(1).totalColumns(3).build());
            Movie movie = em.persist(Movie.builder().title("Broadcast test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Booking booking = em.persist(Booking.builder().user(user).showtime(showtime).totalAmount(1L)
                    .holdExpiresAt(Instant.now().plusSeconds(600)).build());
            seatIds = new java.util.ArrayList<>();
            for (int col = 1; col <= 3; col++) {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).build());
                seatIds.add(seat.getId());
            }
            em.flush();
            userId = user.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
            bookingId = booking.getId();
        });
        inventory.initializeShowtime(showtimeId);
        reset(messaging);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE id=?", bookingId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
        });
    }

    private List<SeatStatusChangedEvent> changes() {
        return events.stream(SeatStatusChangedEvent.class).toList();
    }

    @Test
    void showtimeInventoryIsCreatedOnceForEverySeatOfTheRoom() {
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='AVAILABLE'", Integer.class, showtimeId));
        assertEquals(0, inventory.initializeShowtime(showtimeId), "second call adds nothing");
        assertEquals(Map.of(seatIds.get(0), SeatInventoryStatus.AVAILABLE, seatIds.get(1), SeatInventoryStatus.AVAILABLE,
                seatIds.get(2), SeatInventoryStatus.AVAILABLE), inventory.seatStatuses(showtimeId));
    }

    @Test
    void claimThenReleaseThenSellEachEmitOneEventAndOneMessageAfterCommit() {
        inventory.claim(showtimeId, seatIds.subList(0, 2), bookingId, Instant.now().plusSeconds(600));
        inventory.release(bookingId);
        inventory.claimAndSell(showtimeId, seatIds.subList(2, 3), bookingId);

        assertEquals(List.of(
                new SeatStatusChangedEvent(showtimeId, seatIds.subList(0, 2), SeatInventoryStatus.HELD),
                new SeatStatusChangedEvent(showtimeId, seatIds.subList(0, 2), SeatInventoryStatus.AVAILABLE),
                new SeatStatusChangedEvent(showtimeId, seatIds.subList(2, 3), SeatInventoryStatus.SOLD)), changes());

        ArgumentCaptor<SeatStatusBroadcast> sent = ArgumentCaptor.forClass(SeatStatusBroadcast.class);
        verify(messaging, times(3)).convertAndSend(eq("/topic/showtime/" + showtimeId + "/seats"), sent.capture());
        List<SeatStatusBroadcast> messages = sent.getAllValues();
        assertEquals(List.of("HELD", "AVAILABLE", "SOLD"), messages.stream().map(m -> m.seats().get(0).status()).toList());
        assertEquals(List.of("A1", "A2"), messages.get(0).seats().stream().map(SeatStatusBroadcast.Seat::label).toList());
        assertTrue(messages.get(0).seq() < messages.get(1).seq() && messages.get(1).seq() < messages.get(2).seq(), "seq increases");
        assertEquals(showtimeId, messages.get(0).showtimeId());
        assertDoesNotThrow(() -> Instant.parse(messages.get(0).at()));
    }

    @Test
    void aLostClaimEmitsNothingAndSendsNothing() {
        inventory.claim(showtimeId, seatIds.subList(0, 1), bookingId, Instant.now().plusSeconds(600));
        reset(messaging);
        long before = changes().size();

        assertThrows(SeatConflictException.class,
                () -> inventory.claim(showtimeId, seatIds.subList(0, 2), bookingId + 1, Instant.now().plusSeconds(600)));

        assertEquals(before, changes().size());
        verifyNoInteractions(messaging);
    }

    @Test
    void aRolledBackTransactionNeverReachesClients() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            inventory.claim(showtimeId, seatIds.subList(0, 1), bookingId, Instant.now().plusSeconds(600));
            throw new IllegalStateException("caller fails after claiming");
        }));

        verifyNoInteractions(messaging);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='AVAILABLE'", Integer.class, showtimeId));
    }

    @Test
    void promotionAndTheSweeperAnnounceTheirChanges() {
        inventory.claim(showtimeId, seatIds.subList(0, 2), bookingId, Instant.now().plusSeconds(600));
        assertTrue(inventory.promoteAllToSold(bookingId, 2));
        reset(messaging);

        jdbc.update("UPDATE showtime_seat SET status='HELD',booking_id=?,held_until=? WHERE showtime_id=? AND seat_id=?",
                bookingId, Timestamp.from(Instant.now().minusSeconds(30)), showtimeId, seatIds.get(2));
        assertEquals(1, inventory.releaseExpired());

        ArgumentCaptor<SeatStatusBroadcast> sent = ArgumentCaptor.forClass(SeatStatusBroadcast.class);
        verify(messaging, times(1)).convertAndSend(eq("/topic/showtime/" + showtimeId + "/seats"), sent.capture());
        assertEquals(List.of(seatIds.get(2)), sent.getValue().seats().stream().map(SeatStatusBroadcast.Seat::seatId).toList());
        assertEquals("AVAILABLE", sent.getValue().seats().get(0).status());
        assertEquals(Map.of(seatIds.get(0), SeatInventoryStatus.SOLD, seatIds.get(1), SeatInventoryStatus.SOLD,
                seatIds.get(2), SeatInventoryStatus.AVAILABLE), inventory.seatStatuses(showtimeId));
    }

    @Test
    void aBrokenBroadcastNeverBreaksTheBookingTransaction() {
        doThrow(new IllegalStateException("broker down")).when(messaging).convertAndSend(anyString(), any(Object.class));

        assertDoesNotThrow(() -> inventory.claim(showtimeId, seatIds.subList(0, 1), bookingId, Instant.now().plusSeconds(600)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? AND status='HELD'", Integer.class, showtimeId));
    }

    @Test
    void inventoryCanBeRemovedOnlyWhileNothingIsHeldOrSold() {
        inventory.claim(showtimeId, seatIds.subList(0, 1), bookingId, Instant.now().plusSeconds(600));
        assertFalse(inventory.removeShowtime(showtimeId));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=?", Integer.class, showtimeId));

        inventory.release(bookingId);
        assertTrue(inventory.removeShowtime(showtimeId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=?", Integer.class, showtimeId));
    }
}
