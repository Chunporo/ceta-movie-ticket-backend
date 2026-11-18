package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.service.BookingCreationService;
import com.movie.ticket_booking.service.BookingCreationServiceImpl;
import com.movie.ticket_booking.service.PricingService;
import com.movie.ticket_booking.service.VNPayService;
import com.movie.ticket_booking.service.inventory.InventoryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A VietQR bank-feed webhook settles a booking by the same rules as VNPay: account, amount, once only. */
@DataJpaTest(showSql = false)
@Import({ApplyPaymentResultUseCaseImpl.class, PaymentUrlServiceImpl.class, VNPayService.class, com.movie.ticket_booking.service.payment.vietqr.VietQrService.class, InventoryServiceImpl.class,
        BookingCreationServiceImpl.class, com.movie.ticket_booking.service.BookingExtrasCalculator.class, PricingService.class,
        com.movie.ticket_booking.service.RefundServiceImpl.class})
@TestPropertySource(properties = {"vietqr.bank-id=MB", "vietqr.account-no=0123456789", "vietqr.webhook-key=key-t"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApplyVietQrPaymentTest {
    @Autowired private ApplyPaymentResultUseCase useCase;
    @Autowired private PaymentUrlService paymentUrls;
    @Autowired private BookingCreationService creation;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String email;
    private long userId;
    private long roomId;
    private UUID movieId;
    private long showtimeId;
    private long bookingId;
    private long total;
    private String txnRef;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            email = suffix + "@example.invalid";
            User user = em.persist(User.builder().username(suffix).email(email).passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Momo test").totalRows(1).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Momo test").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            for (int col = 1; col <= 2; col++) {
                Seat seat = em.persist(Seat.builder().room(room).label("A" + col).rowNumber(1).columnNumber(col).build());
                em.persist(ShowtimeSeat.builder().id(new ShowtimeSeatId(showtime.getId(), seat.getId())).showtime(showtime).seat(seat).build());
            }
            em.flush();
            userId = user.getId();
            roomId = room.getId();
            movieId = movie.getId();
            showtimeId = showtime.getId();
        });
        List<Long> seatIds = jdbc.queryForList("SELECT seat_id FROM showtime_seat WHERE showtime_id=? ORDER BY seat_id", Long.class, showtimeId);
        CreateBookingResponse booking = creation.create(email, new CreateBookingRequest(showtimeId, seatIds));
        bookingId = booking.bookingId();
        total = booking.totalAmount();
        txnRef = bookingId + "_1";
        attempt("VIETQR");
    }

    private void attempt(String provider) {
        jdbc.update("INSERT INTO payment_transaction (booking_id,provider,txn_ref,amount,status,created_at) VALUES (?,?,?,?,?,?)",
                bookingId, provider, txnRef, total, "PENDING", Timestamp.from(Instant.now()));
    }

    @AfterEach
    void clean() {
        String bookingIds = "SELECT id FROM booking WHERE showtime_id=" + showtimeId;
        jdbc.update("DELETE FROM refund_queue WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM booking_status_history WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM tickets WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM loyalty_entry WHERE booking_id IN (" + bookingIds + ")");
        jdbc.update("DELETE FROM booking_combo WHERE booking_id IN (" + bookingIds + ")");
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

    private Map<String, String> transfer(String content, long amount, String account, String type) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("id", "9001"); p.put("gateway", "MBBank"); p.put("accountNumber", account); p.put("transferType", type);
        p.put("transferAmount", String.valueOf(amount)); p.put("content", content); p.put("referenceCode", "FT25318");
        return p;
    }

    private String note() {
        return "CETA" + bookingId + "A1";
    }

    @Test
    void anExactTransferWithTheNoteIssuesTheTicketsOnceAndAReplayIsHarmless() {
        PaymentResult first = useCase.applyVietQr(transfer("MBVCB.123 " + note() + " chuyen tien", total, "0123456789", "in"));
        assertEquals(PaymentOutcome.CONFIRMED, first.outcome());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE showtime_id=?", Integer.class, showtimeId));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals(PaymentOutcome.ALREADY_PROCESSED, useCase.applyVietQr(transfer(note(), total, "0123456789", "in")).outcome());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE showtime_id=?", Integer.class, showtimeId));
    }

    @Test
    void aWrongAmountIsRejectedAndLeavesTheBookingPayable() {
        assertEquals(PaymentOutcome.INVALID_AMOUNT, useCase.applyVietQr(transfer(note(), total - 1000, "0123456789", "in")).outcome());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals(PaymentOutcome.CONFIRMED, useCase.applyVietQr(transfer(note(), total, "0123456789", "in")).outcome());
    }

    @Test
    void moneyOutOrToAnotherAccountOrWithoutOurNoteIsNotAPayment() {
        assertEquals(PaymentOutcome.INVALID_MERCHANT, useCase.applyVietQr(transfer(note(), total, "0123456789", "out")).outcome());
        assertEquals(PaymentOutcome.INVALID_MERCHANT, useCase.applyVietQr(transfer(note(), total, "999", "in")).outcome());
        assertEquals(PaymentOutcome.ORDER_NOT_FOUND, useCase.applyVietQr(transfer("tien an trua", total, "0123456789", "in")).outcome());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
    }

    @Test
    void aTransferAfterTheHoldLapsedQueuesARefund() {
        jdbc.update("UPDATE booking SET status='EXPIRED' WHERE id=?", bookingId);
        assertEquals(PaymentOutcome.REFUND_QUEUED, useCase.applyVietQr(transfer(note(), total, "0123456789", "in")).outcome());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, bookingId));
    }

    @Test
    void createUrlGivesAQrImageWhoseNoteMatchesTheTxnRef() {
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id=?", bookingId);
        var res = paymentUrls.create(email, bookingId, "127.0.0.1", "vietqr");
        assertEquals(bookingId + "_1", res.txnRef());
        assertTrue(res.payUrl().contains("MB-0123456789-compact2.png?amount=" + total + "&addInfo=CETA" + bookingId + "A1"), res.payUrl());
    }
}
