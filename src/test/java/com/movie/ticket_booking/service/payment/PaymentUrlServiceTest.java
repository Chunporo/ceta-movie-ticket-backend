package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.dto.booking.CreatePaymentUrlResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.*;
import com.movie.ticket_booking.service.inventory.InventoryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** create-url: attempt rows, txn_ref format, signed URL, and the full loop into the IPN use case. */
@DataJpaTest(showSql = false)
@Import({PaymentUrlServiceImpl.class, ApplyPaymentResultUseCaseImpl.class, VNPayService.class, InventoryServiceImpl.class,
        BookingCreationServiceImpl.class, PricingService.class, RefundServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentUrlServiceTest {
    @Autowired private PaymentUrlService urls;
    @Autowired private ApplyPaymentResultUseCase ipn;
    @Autowired private BookingCreationService creation;
    @Autowired private VNPayService vnPay;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Value("${vnpay.hash-secret}") private String secret;
    @Value("${vnpay.tmn-code}") private String tmn;

    private String email;
    private String strangerEmail;
    private long userId;
    private long strangerId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private CreateBookingResponse booking;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            email = "o" + suffix + "@example.invalid";
            strangerEmail = "s" + suffix + "@example.invalid";
            userId = em.persist(User.builder().username("o" + suffix).email(email).passwordHash("x").build()).getId();
            strangerId = em.persist(User.builder().username("s" + suffix).email(strangerEmail).passwordHash("x").build()).getId();
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Pay URL test").totalRows(1).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Pay URL test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            for (int col = 1; col <= 2; col++) {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).build());
                em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId())).showtime(showtime).seat(seat).build());
            }
            em.flush();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
        booking = creation.create(email, new CreateBookingRequest(showtimeId,
                jdbc.queryForList("SELECT seat_id FROM showtime_seat WHERE showtime_id=? ORDER BY seat_id", Long.class, showtimeId)));
    }

    @AfterEach
    void clean() {
        String bookingIds = "SELECT id FROM booking WHERE showtime_id=" + showtimeId;
        jdbc.update("DELETE FROM refund_queue WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM tickets WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM showtime_seat WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE id=?", showtimeId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(User.class, userId));
            em.remove(em.find(User.class, strangerId));
        });
    }

    @Test
    void createsAPendingAttemptAndASignedUrlForExactlyThatReferenceAndAmount() {
        CreatePaymentUrlResponse response = urls.create(email, booking.bookingId(), "203.0.113.9");

        assertEquals(booking.bookingId() + "_1", response.txnRef());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE txn_ref=? AND provider='VNPAY' AND status='PENDING' AND amount=? AND processed_at IS NULL",
                Integer.class, response.txnRef(), booking.totalAmount()));
        assertTrue(response.payUrl().startsWith("https://example.invalid/vnpay?"));
        Map<String, String> query = query(response.payUrl());
        assertEquals(response.txnRef(), query.get("vnp_TxnRef"));
        assertEquals(String.valueOf(booking.totalAmount() * 100), query.get("vnp_Amount"));
        assertEquals(tmn, query.get("vnp_TmnCode"));
        assertEquals("203.0.113.9", query.get("vnp_IpAddr"));
        assertEquals(DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(booking.holdExpiresAt()), query.get("vnp_ExpireDate"), "VNPay stops accepting at the hold expiry");
        assertTrue(vnPay.verifyCallback(query), "the URL carries a valid signature");
    }

    @Test
    void everyNewAttemptGetsTheNextNumber() {
        assertEquals(booking.bookingId() + "_1", urls.create(email, booking.bookingId(), "127.0.0.1").txnRef());
        assertEquals(booking.bookingId() + "_2", urls.create(email, booking.bookingId(), "127.0.0.1").txnRef());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE booking_id=?", Integer.class, booking.bookingId()));
    }

    @Test
    void someoneElsesBookingIs403AndLeavesNoAttempt() {
        BusinessException ex = assertThrows(BusinessException.class, () -> urls.create(strangerEmail, booking.bookingId(), "127.0.0.1"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE booking_id=?", Integer.class, booking.bookingId()));
    }

    @Test
    void unknownCancelledAndLapsedBookingsAreRefused() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () -> urls.create(email, 987654321L, "127.0.0.1")).getStatus());

        jdbc.update("UPDATE booking SET hold_expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(5)), booking.bookingId());
        assertEquals("HOLD_EXPIRED", assertThrows(BusinessException.class, () -> urls.create(email, booking.bookingId(), "127.0.0.1")).getCode());

        jdbc.update("UPDATE booking SET status='CANCELLED' WHERE id=?", booking.bookingId());
        BusinessException cancelled = assertThrows(BusinessException.class, () -> urls.create(email, booking.bookingId(), "127.0.0.1"));
        assertEquals("BOOKING_NOT_CANCELLABLE", cancelled.getCode());
        assertEquals(HttpStatus.CONFLICT, cancelled.getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE booking_id=?", Integer.class, booking.bookingId()));
    }

    @Test
    void theIssuedReferenceIsAcceptedByTheIpnAndYieldsTickets() {
        CreatePaymentUrlResponse response = urls.create(email, booking.bookingId(), "127.0.0.1");

        PaymentOutcome outcome = ipn.apply(VnPayTestSigner.signed(secret, tmn, response.txnRef(), booking.totalAmount() * 100, "00", "00")).outcome();

        assertEquals(PaymentOutcome.CONFIRMED, outcome);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id=?", Integer.class, booking.bookingId()));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, booking.bookingId()));
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }
}
