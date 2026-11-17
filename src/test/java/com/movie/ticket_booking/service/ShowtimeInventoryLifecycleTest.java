package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.ShowtimeRequest;
import com.movie.ticket_booking.dto.ShowtimeResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A showtime made through the admin API must be bookable: it needs one inventory row per seat. */
@DataJpaTest(showSql = false)
@Import({ShowtimeServiceImpl.class, InventoryServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShowtimeInventoryLifecycleTest {
    @Autowired private ShowtimeService showtimeService;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private long roomId;
    private long otherRoomId;
    private UUID movieId;
    private long userId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            userId = em.persist(User.builder().username(suffix).email(suffix + "@example.invalid").passwordHash("x").build()).getId();
            Room room = em.persist(Room.builder().code("a" + suffix.substring(0, 11)).name("Lifecycle A").totalRows(1).totalColumns(4).build());
            Room other = em.persist(Room.builder().code("b" + suffix.substring(0, 11)).name("Lifecycle B").totalRows(1).totalColumns(2).build());
            for (int col = 1; col <= 4; col++) {
                em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).active(col != 4).build());
            }
            for (int col = 1; col <= 2; col++) {
                em.persist(Seat.builder().room(other).label("B" + col).rowNumber(1).columnNumber(col).build());
            }
            movieId = em.persist(Movie.builder().title("Lifecycle").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build()).getId();
            em.flush();
            roomId = room.getId();
            otherRoomId = other.getId();
        });
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id IN (SELECT id FROM showtime WHERE room_id IN (?,?))", roomId, otherRoomId);
        jdbc.update("DELETE FROM booking WHERE showtime_id IN (SELECT id FROM showtime WHERE room_id IN (?,?))", roomId, otherRoomId);
        jdbc.update("DELETE FROM showtime WHERE room_id IN (?,?)", roomId, otherRoomId);
        jdbc.update("DELETE FROM seat WHERE room_id IN (?,?)", roomId, otherRoomId);
        jdbc.update("DELETE FROM rooms WHERE id IN (?,?)", roomId, otherRoomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
        });
    }

    private ShowtimeRequest request(long room) {
        ShowtimeRequest request = new ShowtimeRequest();
        request.setMovieId(movieId);
        request.setRoomId(room);
        request.setStartTime(Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));
        request.setEndTime(request.getStartTime().plusSeconds(7200));
        request.setBasePrice(BigDecimal.valueOf(90000));
        return request;
    }

    private int rows(long showtimeId, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=? " + where, Integer.class, showtimeId);
    }

    @Test
    void creatingAShowtimeCreatesItsInventory() {
        long id = showtimeService.create(request(roomId)).getId();

        assertEquals(4, rows(id, ""));
        assertEquals(3, rows(id, "AND status='AVAILABLE'"));
        assertEquals(1, rows(id, "AND status='BLOCKED'"), "the inactive seat is blocked");
    }

    @Test
    void changingTheRoomRebuildsInventoryWhenNothingIsBooked() {
        long id = showtimeService.create(request(roomId)).getId();

        showtimeService.update(id, request(otherRoomId));

        assertEquals(2, rows(id, ""));
        assertEquals(2, rows(id, "AND status='AVAILABLE'"));
    }

    @Test
    void aBookedShowtimeCannotChangeRoomOrBeDeleted() {
        ShowtimeResponse created = showtimeService.create(request(roomId));
        long id = created.getId();
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,combo_amount,hold_expires_at,created_at) VALUES (?,?,'PENDING',1,0,?,?)",
                userId, id, java.sql.Timestamp.from(Instant.now().plusSeconds(600)), java.sql.Timestamp.from(Instant.now()));

        BusinessException change = assertThrows(BusinessException.class, () -> showtimeService.update(id, request(otherRoomId)));
        BusinessException delete = assertThrows(BusinessException.class, () -> showtimeService.delete(id));

        assertEquals("SHOWTIME_IN_USE", change.getCode());
        assertEquals(HttpStatus.CONFLICT, delete.getStatus());
        assertEquals(4, rows(id, ""), "inventory untouched");
    }

    @Test
    void deletingAnUnusedShowtimeRemovesItsInventoryToo() {
        long id = showtimeService.create(request(roomId)).getId();

        showtimeService.delete(id);

        assertEquals(0, rows(id, ""));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM showtime WHERE id=?", Integer.class, id));
    }
}
