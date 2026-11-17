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
import com.movie.ticket_booking.service.payment.momo.MomoGateway;
import com.movie.ticket_booking.service.payment.momo.MomoService;
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

/** A MoMo callback settles a booking by the same rules as VNPay: signature, merchant, amount, once only. */
@DataJpaTest(showSql = false)
@Import({ApplyPaymentResultUseCaseImpl.class, PaymentUrlServiceImpl.class, VNPayService.class, MomoService.class, InventoryServiceImpl.class,
        BookingCreationServiceImpl.class, com.movie.ticket_booking.service.BookingExtrasCalculator.class, PricingService.class,
        com.movie.ticket_booking.service.RefundServiceImpl.class})
@TestPropertySource(properties = {"momo.partner-code=MOMOTEST", "momo.access-key=access-t", "momo.secret-key=secret-t",
        "momo.redirect-url=http://localhost/return", "momo.ipn-url=http://localhost/ipn"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApplyMomoPaymentTest {
    @MockBean private MomoGateway gateway; // never talk to MoMo
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
        attempt("MOMO");
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

    /** What MoMo posts to the IPN, signed the way MoMo documents it. */
    private Map<String, String> momo(String orderId, long amount, int resultCode, String partner) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("partnerCode", partner); p.put("orderId", orderId); p.put("requestId", "req-1"); p.put("amount", String.valueOf(amount));
        p.put("orderInfo", "Dat ve phim"); p.put("orderType", "momo_wallet"); p.put("transId", "4086000009");
        p.put("resultCode", String.valueOf(resultCode)); p.put("message", resultCode == 0 ? "Successful." : "Failed.");
        p.put("payType", "qr"); p.put("responseTime", "1763394000000"); p.put("extraData", "");
        String raw = "accessKey=access-t&amount=" + amount + "&extraData=&message=" + p.get("message") + "&orderId=" + orderId
                + "&orderInfo=Dat ve phim&orderType=momo_wallet&partnerCode=" + partner + "&payType=qr&requestId=req-1"
                + "&responseTime=1763394000000&resultCode=" + resultCode + "&transId=4086000009";
        p.put("signature", MomoService.hmacSha256("secret-t", raw));
        return p;
    }

    private String one(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    @Test
    void aSuccessfulPaymentIssuesTicketsOnceAndAReplayIsAlreadyProcessed() {
        Map<String, String> payload = momo(txnRef, total, 0, "MOMOTEST");

        assertEquals(PaymentOutcome.CONFIRMED, useCase.applyMomo(payload).outcome());
        assertEquals(PaymentOutcome.ALREADY_PROCESSED, useCase.applyMomo(payload).outcome());
        assertEquals(PaymentOutcome.ALREADY_PROCESSED, useCase.applyMomo(payload).outcome());

        assertEquals("PAID", one("SELECT status FROM booking WHERE id=" + bookingId));
        assertEquals("2", one("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId));
        assertEquals("SUCCESS", one("SELECT status FROM payment_transaction WHERE txn_ref='" + txnRef + "'"));
        assertEquals("4086000009", one("SELECT provider_txn FROM payment_transaction WHERE txn_ref='" + txnRef + "'"));
        assertEquals("MOMO_PAID", one("SELECT reason FROM booking_status_history WHERE to_status='PAID' AND booking_id=" + bookingId));
    }

    @Test
    void aPaymentOfTheWrongAmountIsRefusedAndTheRightOneStillWorksAfterwards() {
        assertEquals(PaymentOutcome.INVALID_AMOUNT, useCase.applyMomo(momo(txnRef, total - 1000, 0, "MOMOTEST")).outcome());
        assertEquals("PENDING", one("SELECT status FROM booking WHERE id=" + bookingId));
        assertEquals(PaymentOutcome.CONFIRMED, useCase.applyMomo(momo(txnRef, total, 0, "MOMOTEST")).outcome());
    }

    @Test
    void aFailedOrDeniedPaymentLeavesTheBookingPending() {
        assertEquals(PaymentOutcome.PAYMENT_FAILED, useCase.applyMomo(momo(txnRef, total, 1006, "MOMOTEST")).outcome());
        assertEquals("PENDING", one("SELECT status FROM booking WHERE id=" + bookingId));
        assertEquals("FAILED", one("SELECT status FROM payment_transaction WHERE txn_ref='" + txnRef + "'"));
        assertEquals("0", one("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId));
    }

    @Test
    void aForgedSignatureAndAForeignPartnerAreRefused() {
        Map<String, String> forged = momo(txnRef, total, 0, "MOMOTEST");
        forged.put("signature", "0".repeat(64));
        assertEquals(PaymentOutcome.INVALID_SIGNATURE, useCase.applyMomo(forged).outcome());

        assertEquals(PaymentOutcome.INVALID_MERCHANT, useCase.applyMomo(momo(txnRef, total, 0, "SOMEONEELSE")).outcome());
        assertEquals("PENDING", one("SELECT status FROM booking WHERE id=" + bookingId));
    }

    @Test
    void aMomoCallbackCannotSettleAVnpayAttemptWithTheSameReference() {
        jdbc.update("UPDATE payment_transaction SET provider='VNPAY' WHERE txn_ref=?", txnRef);
        assertEquals(PaymentOutcome.ORDER_NOT_FOUND, useCase.applyMomo(momo(txnRef, total, 0, "MOMOTEST")).outcome());
        assertEquals("PENDING", one("SELECT status FROM booking WHERE id=" + bookingId));
    }

    // ---- starting a MoMo payment ---------------------------------------------------------------------------------

    @Test
    void startingAMomoPaymentRecordsAMomoAttemptForTheBookingTotalAndReturnsMomosUrl() {
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id=?", bookingId);
        org.mockito.Mockito.when(gateway.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new MomoGateway.CreateResult(0, "Successful.", "http://momo.test/pay/abc"));

        var response = paymentUrls.create(email, bookingId, "10.0.0.1", "momo");

        assertEquals("http://momo.test/pay/abc", response.payUrl());
        assertEquals(bookingId + "_1", response.txnRef());
        assertEquals("MOMO", one("SELECT provider FROM payment_transaction WHERE txn_ref='" + response.txnRef() + "'"));
        assertEquals(String.valueOf(total), one("SELECT amount FROM payment_transaction WHERE txn_ref='" + response.txnRef() + "'"));
        assertEquals("PENDING", one("SELECT status FROM payment_transaction WHERE txn_ref='" + response.txnRef() + "'"));
    }

    @Test
    void whenMomoRefusesNoAttemptIsLeftBehind() {
        jdbc.update("DELETE FROM payment_transaction WHERE booking_id=?", bookingId);
        org.mockito.Mockito.when(gateway.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new MomoGateway.CreateResult(1001, "Insufficient", null));

        var error = assertThrows(com.movie.ticket_booking.exception.BusinessException.class, () -> paymentUrls.create(email, bookingId, "10.0.0.1", "MOMO"));

        assertEquals("PAYMENT_PROVIDER_ERROR", error.getCode());
        assertEquals("0", one("SELECT COUNT(*) FROM payment_transaction WHERE booking_id=" + bookingId));
    }

    @Test
    void anUnknownProviderIsRefused() {
        var error = assertThrows(com.movie.ticket_booking.exception.BusinessException.class, () -> paymentUrls.create(email, bookingId, "10.0.0.1", "PAYPAL"));
        assertEquals("INVALID_REQUEST", error.getCode());
    }
}
