package com.movie.ticket_booking.service.ticket;

import com.movie.ticket_booking.dto.booking.CheckInRequest;
import com.movie.ticket_booking.dto.booking.CheckInResponse;
import com.movie.ticket_booking.entity.*;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({CheckInServiceImpl.class, TicketQrServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckInServiceTest {
    @Autowired private CheckInService checkIn;
    @Autowired private TicketQrService qr;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String staffEmail;
    private long staffId;
    private long customerId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private long bookingId;
    private long ticketId;
    private String code;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            staffEmail = "s" + suffix + "@example.invalid";
            staffId = em.persist(User.builder().username("s" + suffix).email(staffEmail).passwordHash("x").build()).getId();
            User customer = em.persist(User.builder().username("c" + suffix).email("c" + suffix + "@example.invalid").passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Door room").totalRows(1).totalColumns(1).build());
            Movie movie = em.persist(Movie.builder().title("Door movie").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Seat seat = em.persist(Seat.builder().room(room).label("F7").rowNumber(1).columnNumber(1).build());
            Booking booking = em.persist(Booking.builder().user(customer).showtime(showtime).status(BookingStatus.PAID).totalAmount(100000L)
                    .holdExpiresAt(Instant.now().plusSeconds(600)).paidAt(Instant.now()).build());
            code = "TCK-" + suffix.substring(0, 12);
            Ticket ticket = em.persist(Ticket.builder().booking(booking).showtime(showtime).seat(seat)
                    .price(BigDecimal.valueOf(100000)).code(code).build());
            em.flush();
            customerId = customer.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
            bookingId = booking.getId();
            ticketId = ticket.getId();
        });
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM tickets WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, staffId));
            em.remove(em.find(User.class, customerId));
        });
    }

    private String qrFor(long id) {
        return qr.sign(id, Instant.now().plus(2, ChronoUnit.HOURS));
    }

    private void startShowtimeAt(Instant start) {
        jdbc.update("UPDATE showtime SET start_time=?, end_time=? WHERE id=?", Timestamp.from(start), Timestamp.from(start.plusSeconds(7200)), showtimeId);
    }

    private BusinessException rejected(CheckInRequest request) {
        return assertThrows(BusinessException.class, () -> checkIn.checkIn(staffEmail, request));
    }

    @Test
    void aGenuineQrChecksTheTicketInAndShowsTheStaffWhatTheyNeed() {
        CheckInResponse response = checkIn.checkIn(staffEmail, new CheckInRequest(qrFor(ticketId), null));

        assertEquals(ticketId, response.ticketId());
        assertEquals("F7", response.seatLabel());
        assertEquals("Door movie", response.movieTitle());
        assertEquals("Door room", response.roomName());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE id=? AND checked_in_at IS NOT NULL AND checked_in_by=?", Integer.class, ticketId, staffId));
    }

    @Test
    void theSecondScanIs409AndTellsWhenItWasFirstUsed() {
        checkIn.checkIn(staffEmail, new CheckInRequest(qrFor(ticketId), null));

        BusinessException again = rejected(new CheckInRequest(qrFor(ticketId), null));
        BusinessException typed = rejected(new CheckInRequest(null, code));

        for (BusinessException ex : new BusinessException[]{again, typed}) {
            assertEquals("ALREADY_CHECKED_IN", ex.getCode());
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals(1, ex.getDetails().size());
        }
    }

    @Test
    void aTypedCodeWorksAndAnUnknownOneIs404() {
        assertEquals(ticketId, checkIn.checkIn(staffEmail, new CheckInRequest(null, " " + code + " ")).ticketId());

        BusinessException ex = rejected(new CheckInRequest(null, "TCK-nope"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void forgedAndExpiredQrCodesAreRejectedWithoutTouchingTheTicket() {
        String genuine = qrFor(ticketId);
        String forged = genuine.substring(0, genuine.length() - 2) + (genuine.endsWith("AA") ? "BB" : "AA");
        String otherTicket = genuine.replace("CETA1." + ticketId + ".", "CETA1." + (ticketId + 1) + ".");
        String expired = qr.sign(ticketId, Instant.now().minusSeconds(5));

        assertEquals("INVALID_QR", rejected(new CheckInRequest(forged, null)).getCode());
        assertEquals("INVALID_QR", rejected(new CheckInRequest(otherTicket, null)).getCode());
        assertEquals("INVALID_QR", rejected(new CheckInRequest("not a qr", null)).getCode());
        assertEquals("QR_EXPIRED", rejected(new CheckInRequest(expired, null)).getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE id=? AND checked_in_at IS NOT NULL", Integer.class, ticketId));
    }

    @Test
    void checkInOpensAnHourBeforeTheShowAndClosesTwoHoursAfterTheStart() {
        startShowtimeAt(Instant.now().plus(3, ChronoUnit.HOURS));
        assertEquals("CHECKIN_NOT_OPEN", rejected(new CheckInRequest(null, code)).getCode());

        startShowtimeAt(Instant.now().minus(3, ChronoUnit.HOURS));
        assertEquals("CHECKIN_CLOSED", rejected(new CheckInRequest(null, code)).getCode());

        startShowtimeAt(Instant.now().plus(50, ChronoUnit.MINUTES));
        assertDoesNotThrow(() -> checkIn.checkIn(staffEmail, new CheckInRequest(null, code)));
    }

    @Test
    void aTicketOfARefundedOrCancelledBookingIsNotValid() {
        for (String status : new String[]{"REFUNDED", "CANCELLED", "EXPIRED"}) {
            jdbc.update("UPDATE booking SET status=? WHERE id=?", status, bookingId);
            BusinessException ex = rejected(new CheckInRequest(null, code));
            assertEquals("TICKET_NOT_VALID", ex.getCode(), status);
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE id=? AND checked_in_at IS NOT NULL", Integer.class, ticketId));
    }

    @Test
    void exactlyOneOfQrOrCodeIsRequired() {
        assertEquals("INVALID_REQUEST", rejected(new CheckInRequest(null, null)).getCode());
        assertEquals("INVALID_REQUEST", rejected(new CheckInRequest(" ", "")).getCode());
        assertEquals("INVALID_REQUEST", rejected(new CheckInRequest(qrFor(ticketId), code)).getCode());
        assertEquals("INVALID_REQUEST", rejected(null).getCode());
    }
}
