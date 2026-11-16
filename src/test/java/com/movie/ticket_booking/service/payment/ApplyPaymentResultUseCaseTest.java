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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** T6: the five-times-same-payload gate plus every rejection path, on H2. */
@DataJpaTest(showSql = false)
@Import({ApplyPaymentResultUseCaseImpl.class, VNPayService.class, InventoryServiceImpl.class,
        BookingCreationServiceImpl.class, PricingService.class, com.movie.ticket_booking.service.RefundServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApplyPaymentResultUseCaseTest {
    @Autowired private ApplyPaymentResultUseCase useCase;
    @Autowired private BookingCreationService creation;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Value("${vnpay.hash-secret}") private String hashSecret;
    @Value("${vnpay.tmn-code}") private String tmnCode;

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
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Payment test").totalRows(1).totalColumns(2).build());
            Movie movie = em.persist(Movie.builder().title("Payment test").slug(suffix).posterUrl("https://example.invalid/p")
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
        jdbc.update("INSERT INTO payment_transaction (booking_id,provider,txn_ref,amount,status,created_at) VALUES (?,?,?,?,?,?)",
                bookingId, "VNPAY", txnRef, total, "PENDING", Timestamp.from(Instant.now()));
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
        });
    }

    @Test
    void samePayloadFiveTimesCreatesTicketsOnceAndFlagsOnce() {
        Map<String, String> payload = signed(txnRef, total * 100, "00", "00", tmnCode);

        List<PaymentOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            outcomes.add(useCase.apply(payload).outcome());
        }
        Timestamp firstFlag = processedAt();
        useCase.apply(payload);

        assertEquals(PaymentOutcome.CONFIRMED, outcomes.get(0));
        assertEquals(List.of(PaymentOutcome.ALREADY_PROCESSED), outcomes.subList(1, 5).stream().distinct().toList());
        assertEquals(2, count("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId), "one ticket per seat, once");
        assertEquals(total, jdbc.queryForObject("SELECT SUM(price) FROM tickets WHERE showtime_id=?", Long.class, showtimeId));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals(2, count("SELECT COUNT(*) FROM showtime_seat WHERE status='SOLD' AND showtime_id=" + showtimeId));
        assertEquals(1, count("SELECT COUNT(*) FROM booking_status_history WHERE to_status='PAID' AND booking_id=" + bookingId));
        assertEquals(firstFlag, processedAt(), "processed_at is set exactly once");
        assertEquals(0, count("SELECT COUNT(*) FROM refund_queue"));
    }

    @Test
    void badSignatureChangesNothing() {
        Map<String, String> payload = signed(txnRef, total * 100, "00", "00", tmnCode);
        payload.put("vnp_Amount", String.valueOf(total * 100 + 100)); // tampered after signing

        assertEquals(PaymentOutcome.INVALID_SIGNATURE, useCase.apply(payload).outcome());

        assertUntouched();
    }

    @Test
    void wrongAmountIsRejectedWithoutBurningTheIdempotencyFlag() {
        assertEquals(PaymentOutcome.INVALID_AMOUNT, useCase.apply(signed(txnRef, (total - 1000) * 100, "00", "00", tmnCode)).outcome());
        assertEquals(PaymentOutcome.INVALID_AMOUNT, useCase.apply(signed(txnRef, total * 100 + 50, "00", "00", tmnCode)).outcome());

        assertUntouched();
        assertEquals(PaymentOutcome.CONFIRMED, useCase.apply(signed(txnRef, total * 100, "00", "00", tmnCode)).outcome(),
                "the genuine payment still succeeds afterwards");
    }

    @Test
    void foreignMerchantCodeIsRejected() {
        assertEquals(PaymentOutcome.INVALID_MERCHANT, useCase.apply(signed(txnRef, total * 100, "00", "00", "OTHER")).outcome());
        assertUntouched();
    }

    @Test
    void responseCodeAloneIsNotTrustedAndFailedPaymentLeavesBookingPending() {
        assertEquals(PaymentOutcome.PAYMENT_FAILED, useCase.apply(signed(txnRef, total * 100, "24", "02", tmnCode)).outcome());

        assertEquals(0, count("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM payment_transaction WHERE txn_ref=?", String.class, txnRef));
    }

    @Test
    void successResponseWithFailedTransactionStatusIsNotAPayment() {
        assertEquals(PaymentOutcome.PAYMENT_FAILED, useCase.apply(signed(txnRef, total * 100, "00", "02", tmnCode)).outcome());
        assertEquals(0, count("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId));
    }

    @Test
    void unknownTxnRefIsNotFound() {
        assertEquals(PaymentOutcome.ORDER_NOT_FOUND, useCase.apply(signed("999999_1", total * 100, "00", "00", tmnCode)).outcome());
        assertUntouched();
    }

    @Test
    void paymentAfterCancellationQueuesOneRefundAndNoTickets() {
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE showtime_id=?", showtimeId);
        jdbc.update("UPDATE booking SET status='CANCELLED' WHERE id=?", bookingId);

        assertEquals(PaymentOutcome.REFUND_QUEUED, useCase.apply(signed(txnRef, total * 100, "00", "00", tmnCode)).outcome());

        assertEquals(0, count("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId));
        assertEquals(1, count("SELECT COUNT(*) FROM refund_queue WHERE reason='LATE_PAYMENT' AND booking_id=" + bookingId));
        assertEquals(total, jdbc.queryForObject("SELECT amount FROM refund_queue WHERE booking_id=?", Long.class, bookingId));
    }

    @Test
    void lateIpnOnAnExpiredBookingQueuesExactlyOneRefundEvenWhenRepeatedOrRetriedWithANewTxnRef() {
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE showtime_id=?", showtimeId);
        jdbc.update("UPDATE booking SET status='EXPIRED' WHERE id=?", bookingId);
        Map<String, String> payload = signed(txnRef, total * 100, "00", "00", tmnCode);

        assertEquals(PaymentOutcome.REFUND_QUEUED, useCase.apply(payload).outcome());
        assertEquals(PaymentOutcome.ALREADY_PROCESSED, useCase.apply(payload).outcome());
        assertEquals(1, count("SELECT COUNT(*) FROM refund_queue WHERE booking_id=" + bookingId));

        String secondRef = bookingId + "_2";
        jdbc.update("INSERT INTO payment_transaction (booking_id,provider,txn_ref,amount,status,created_at) VALUES (?,?,?,?,?,?)",
                bookingId, "VNPAY", secondRef, total, "PENDING", Timestamp.from(Instant.now()));
        assertEquals(PaymentOutcome.REFUND_QUEUED, useCase.apply(signed(secondRef, total * 100, "00", "00", tmnCode)).outcome());

        assertEquals(1, count("SELECT COUNT(*) FROM refund_queue WHERE booking_id=" + bookingId), "unique(booking_id)");
        assertEquals(0, count("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId));
    }

    @Test
    void paymentAfterTheHoldLapsedQueuesRefundAndExpiresBooking() {
        jdbc.update("UPDATE showtime_seat SET held_until=? WHERE showtime_id=?", Timestamp.from(Instant.now().minusSeconds(60)), showtimeId);

        assertEquals(PaymentOutcome.REFUND_QUEUED, useCase.apply(signed(txnRef, total * 100, "00", "00", tmnCode)).outcome());

        assertEquals(0, count("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId));
        assertEquals(1, count("SELECT COUNT(*) FROM refund_queue WHERE booking_id=" + bookingId));
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals(0, count("SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=" + showtimeId + " AND status<>'AVAILABLE'"));
    }

    private void assertUntouched() {
        assertNull(processedAt(), "processed_at must stay NULL");
        assertEquals(0, count("SELECT COUNT(*) FROM tickets WHERE showtime_id=" + showtimeId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals(2, count("SELECT COUNT(*) FROM showtime_seat WHERE status='HELD' AND showtime_id=" + showtimeId));
        assertEquals(0, count("SELECT COUNT(*) FROM refund_queue"));
    }

    private Timestamp processedAt() {
        return jdbc.queryForObject("SELECT processed_at FROM payment_transaction WHERE txn_ref=?", Timestamp.class, txnRef);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    /** Builds a VNPay callback signed exactly as VNPayService verifies it. */
    private Map<String, String> signed(String ref, long vnpAmount, String responseCode, String transactionStatus, String tmn) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TmnCode", tmn);
        params.put("vnp_TxnRef", ref);
        params.put("vnp_Amount", String.valueOf(vnpAmount));
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", transactionStatus);
        params.put("vnp_TransactionNo", "14000001");
        params.put("vnp_OrderInfo", "Dat ve phim");
        StringBuilder data = new StringBuilder();
        params.forEach((k, v) -> data.append(data.length() == 0 ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.US_ASCII)));
        params.put("vnp_SecureHash", hmac(data.toString()));
        return new LinkedHashMap<>(params);
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(hashSecret.trim().getBytes(StandardCharsets.US_ASCII), "HmacSHA512"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(data.getBytes(StandardCharsets.US_ASCII))) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
