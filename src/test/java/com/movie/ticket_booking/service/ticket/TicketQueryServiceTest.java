package com.movie.ticket_booking.service.ticket;

import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.dto.booking.TicketResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.BusinessException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({TicketQueryServiceImpl.class, TicketQrServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TicketQueryServiceTest {
    @Autowired private TicketQueryService service;
    @Autowired private TicketQrService qr;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String aliceEmail;
    private String bobEmail;
    private long aliceId;
    private long bobId;
    private long roomId;
    private UUID movieId;
    private final List<Long> showtimeIds = new ArrayList<>();
    private final List<Long> aliceTicketIds = new ArrayList<>();
    private long bobTicketId;
    private Instant earlier;
    private Instant later;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            aliceEmail = "a" + suffix + "@example.invalid";
            bobEmail = "b" + suffix + "@example.invalid";
            User alice = em.persist(User.builder().username("a" + suffix).email(aliceEmail).passwordHash("x").build());
            User bob = em.persist(User.builder().username("b" + suffix).email(bobEmail).passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Ticket room").totalRows(1).totalColumns(3).build());
            Movie movie = em.persist(Movie.builder().title("Ticket movie").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            earlier = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            later = earlier.plus(2, ChronoUnit.DAYS);
            Showtime first = em.persist(Showtime.builder().movie(movie).room(room).startTime(earlier)
                    .endTime(earlier.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Showtime second = em.persist(Showtime.builder().movie(movie).room(room).startTime(later)
                    .endTime(later.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Seat a1 = em.persist(Seat.builder().room(room).label("A1").rowNumber(1).columnNumber(1).build());
            Seat a2 = em.persist(Seat.builder().room(room).label("A2").rowNumber(1).columnNumber(2).build());
            Seat a3 = em.persist(Seat.builder().room(room).label("A3").rowNumber(1).columnNumber(3).build());
            Booking aliceEarly = paid(alice, first);
            Booking aliceLate = paid(alice, second);
            Booking bobBooking = paid(bob, first);
            aliceTicketIds.add(ticket(aliceEarly, first, a1, "T-A1").getId());
            aliceTicketIds.add(ticket(aliceEarly, first, a2, "T-A2").getId());
            aliceTicketIds.add(ticket(aliceLate, second, a1, "T-A1-LATE").getId());
            bobTicketId = ticket(bobBooking, first, a3, "T-B3").getId();
            em.flush();
            aliceId = alice.getId();
            bobId = bob.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeIds.addAll(List.of(first.getId(), second.getId()));
        });
    }

    private Booking paid(User user, Showtime showtime) {
        return em.persist(Booking.builder().user(user).showtime(showtime).status(BookingStatus.PAID).totalAmount(200000L)
                .holdExpiresAt(Instant.now().plusSeconds(600)).paidAt(Instant.now()).build());
    }

    private Ticket ticket(Booking booking, Showtime showtime, Seat seat, String code) {
        return em.persist(Ticket.builder().booking(booking).showtime(showtime).seat(seat)
                .price(BigDecimal.valueOf(100000)).code(code).build());
    }

    @AfterEach
    void clean() {
        String in = showtimeIds.toString().replaceAll("[\\[\\]]", "");
        jdbc.update("DELETE FROM tickets WHERE showtime_id IN (" + in + ")");
        jdbc.update("DELETE FROM booking WHERE showtime_id IN (" + in + ")");
        jdbc.update("DELETE FROM showtime WHERE id IN (" + in + ")");
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, aliceId));
            em.remove(em.find(User.class, bobId));
        });
    }

    @Test
    void myTicketsListsOnlyMyOwnLatestShowFirstWithEverythingTheAppNeeds() {
        PageResponse<TicketResponse> page = service.myTickets(aliceEmail, 0, 20);

        assertEquals(3, page.totalItems());
        assertEquals(List.of("T-A1-LATE", "T-A1", "T-A2"), page.items().stream().map(TicketResponse::code).toList(),
                "latest showtime first, then ticket id");
        TicketResponse first = page.items().get(1);
        assertEquals("A1", first.seatLabel());
        assertEquals(100000L, first.price());
        assertEquals("Ticket movie", first.showtime().movieTitle());
        assertEquals("Ticket room", first.showtime().roomName());
        assertEquals(earlier, first.showtime().startTime());
        assertTrue(page.items().stream().noneMatch(t -> t.ticketId() == bobTicketId), "never someone else's ticket");
    }

    @Test
    void paginationAndSizeCap() {
        PageResponse<TicketResponse> p0 = service.myTickets(aliceEmail, 0, 2);
        PageResponse<TicketResponse> p1 = service.myTickets(aliceEmail, 1, 2);

        assertEquals(2, p0.items().size());
        assertEquals(1, p1.items().size());
        assertEquals(2, p0.totalPages());
        assertEquals(50, service.myTickets(aliceEmail, 0, 10_000).size(), "page size is capped at 50");
        assertEquals(0, service.myTickets(bobEmail, 5, 20).items().size(), "past the last page is just empty");
    }

    @Test
    void theQrOfATicketVerifiesBackToThatTicketAndExpiresTwoHoursAfterTheStart() {
        TicketResponse ticket = service.get(aliceEmail, aliceTicketIds.get(0));

        assertEquals(aliceTicketIds.get(0), qr.verify(ticket.qrPayload()));
        long exp = Long.parseLong(ticket.qrPayload().split("\\.")[2]);
        assertEquals(earlier.plus(2, ChronoUnit.HOURS).getEpochSecond(), exp);
        assertEquals("T-A1", ticket.code());
        assertNotEquals(ticket.qrPayload(), service.get(aliceEmail, aliceTicketIds.get(1)).qrPayload());
    }

    @Test
    void someoneElsesOrUnknownTicketIsIndistinguishablyNotFound() {
        BusinessException other = assertThrows(BusinessException.class, () -> service.get(aliceEmail, bobTicketId));
        BusinessException unknown = assertThrows(BusinessException.class, () -> service.get(aliceEmail, 987654321L));

        assertEquals(HttpStatus.NOT_FOUND, other.getStatus());
        assertEquals(other.getCode(), unknown.getCode());
        assertEquals(other.getMessage(), unknown.getMessage());
    }

    @Test
    void aTicketCarriesWhatTheAppDrawsOnItsFace() {
        com.movie.ticket_booking.dto.booking.TicketResponse t = service.get(aliceEmail, aliceTicketIds.get(0));

        assertEquals("Ticket movie", t.showtime().movieTitle());
        assertEquals("https://example.invalid/p", t.showtime().posterUrl());
        assertEquals(MovieFormat.TWO_D, t.showtime().format());
        assertFalse(t.used());
        assertNull(t.showtime().cinemaName(), "a room built without a cinema still answers");
    }

    @Test
    void aScannedTicketIsMarkedUsed() {
        jdbc.update("UPDATE tickets SET checked_in_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now()), aliceTicketIds.get(0));
        assertTrue(service.get(aliceEmail, aliceTicketIds.get(0)).used());
        assertFalse(service.get(aliceEmail, aliceTicketIds.get(1)).used());
    }
}
