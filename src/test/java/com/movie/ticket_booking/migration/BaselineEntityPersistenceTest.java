package com.movie.ticket_booking.migration;

import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class BaselineEntityPersistenceTest {
    @Autowired
    private TestEntityManager em;

    @Autowired
    private BookingRepository bookings;

    @Test
    void heldInventoryRoundTripsCompositeIdentityOwnerAndExpiry() {
        Booking booking = booking();
        Seat seat = em.persist(Seat.builder().room(booking.getShowtime().getRoom())
                .label("A1").rowNumber(1).columnNumber(1).build());
        ShowtimeSeatId id = new ShowtimeSeatId(booking.getShowtime().getId(), seat.getId());
        Instant expiry = booking.getHoldExpiresAt();
        em.persistAndFlush(ShowtimeSeat.builder().id(id).showtime(booking.getShowtime()).seat(seat)
                .status(SeatInventoryStatus.HELD).booking(booking).heldUntil(expiry).build());
        em.clear();
        ShowtimeSeat held = em.find(ShowtimeSeat.class, id);
        assertEquals(SeatInventoryStatus.HELD, held.getStatus());
        assertEquals(booking.getId(), held.getBooking().getId());
        assertEquals(expiry, held.getHeldUntil());
        assertEquals(100000L, em.find(Booking.class, booking.getId()).getTotalAmount());
    }

    @Test
    void paymentJsonRefundAndAuditSurviveReload() {
        Booking booking = booking();
        PaymentTransaction payment = em.persist(PaymentTransaction.builder().booking(booking)
                .provider("VNPAY").txnRef(booking.getId() + "_1").amount(100000L)
                .status("PENDING").rawPayload("{\"vnp_ResponseCode\":\"00\"}").build());
        RefundQueue refund = em.persist(RefundQueue.builder().booking(booking).amount(100000L)
                .reason(RefundReason.LATE_PAYMENT).build());
        BookingStatusHistory audit = em.persist(BookingStatusHistory.builder().booking(booking)
                .toStatus("PENDING").reason("Created").build());
        em.flush();
        em.clear();
        assertEquals("{\"vnp_ResponseCode\":\"00\"}", em.find(PaymentTransaction.class, payment.getId()).getRawPayload());
        assertNull(em.find(PaymentTransaction.class, payment.getId()).getProcessedAt());
        assertEquals(RefundStatus.PENDING, em.find(RefundQueue.class, refund.getId()).getStatus());
        assertEquals("PENDING", em.find(BookingStatusHistory.class, audit.getId()).getToStatus());
        assertNotNull(em.find(BookingStatusHistory.class, audit.getId()).getCreatedAt());
    }

    @Test
    void bookingLookupKeepsAccountEmailAvailableAfterDetaching() {
        Long id = booking().getId();
        em.flush();
        em.clear();
        Booking loaded = bookings.findById(id).orElseThrow();
        em.clear();
        assertEquals("schema@example.invalid", loaded.getUser().getEmail());
    }

    private Booking booking() {
        User user = em.persist(User.builder().username("schema-test").email("schema@example.invalid")
                .passwordHash("test-only").build());
        Room room = em.persist(Room.builder().code("SCHEMA").name("Schema room").totalRows(8).totalColumns(15).build());
        Movie movie = em.persist(Movie.builder().title("Schema movie").slug("schema-movie")
                .posterUrl("https://example.invalid/poster").ageRating(AgeRating.P)
                .status(MovieStatus.NOW_SHOWING).duration(120).build());
        Instant tomorrow = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(tomorrow)
                .endTime(tomorrow.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
        return em.persist(Booking.builder().user(user).showtime(showtime).totalAmount(100000L)
                .holdExpiresAt(tomorrow.minusSeconds(600)).build());
    }
}
