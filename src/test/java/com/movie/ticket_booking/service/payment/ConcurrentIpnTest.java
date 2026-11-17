package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.service.inventory.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Five identical IPNs racing on real MySQL must yield exactly one set of tickets. */
@Tag("it-mysql")
@ActiveProfiles("it-mysql")
@SpringBootTest
class ConcurrentIpnTest {
    private static final long USER_ID = 92_000L;
    private static final int THREADS = 5;

    @Autowired private ApplyPaymentResultUseCase useCase;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;
    @Value("${vnpay.hash-secret}") private String hashSecret;
    @Value("${vnpay.tmn-code}") private String tmnCode;

    private long showtimeId;
    private long bookingId;
    private String txnRef;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        cleanUp();
        showtimeId = jdbc.queryForObject("SELECT MIN(id) FROM showtime", Long.class);
        jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE showtime_id=?", showtimeId);
        seatIds = jdbc.queryForList("SELECT seat_id FROM showtime_seat WHERE showtime_id=? ORDER BY seat_id LIMIT 2", Long.class, showtimeId);
        jdbc.update("INSERT INTO users (id,username,email,password_hash,enabled,created_at,updated_at) VALUES (?,?,?,'x',1,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                USER_ID, "ipn-test", "ipn-test@example.invalid");
        jdbc.update("INSERT INTO booking (user_id,showtime_id,status,total_amount,hold_expires_at) VALUES (?,?,'PENDING',200000,UTC_TIMESTAMP() + INTERVAL 10 MINUTE)", USER_ID, showtimeId);
        bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE user_id=?", Long.class, USER_ID);
        for (Long seatId : seatIds) {
            jdbc.update("INSERT INTO booking_seat (booking_id,seat_id,price) VALUES (?,?,100000)", bookingId, seatId);
        }
        inventory.claim(showtimeId, seatIds, bookingId, Instant.now().plusSeconds(600));
        txnRef = bookingId + "_1";
        jdbc.update("INSERT INTO payment_transaction (booking_id,provider,txn_ref,amount,status) VALUES (?,'VNPAY',?,200000,'PENDING')", bookingId, txnRef);
    }

    @AfterEach
    void cleanUp() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM booking WHERE user_id=?", Long.class, USER_ID);
        for (Long id : ids) {
            jdbc.update("DELETE FROM tickets WHERE booking_id=?", id);
            jdbc.update("DELETE FROM booking_status_history WHERE booking_id=?", id);
            jdbc.update("DELETE FROM refund_queue WHERE booking_id=?", id);
            jdbc.update("DELETE FROM payment_transaction WHERE booking_id=?", id);
            jdbc.update("DELETE FROM booking_seat WHERE booking_id=?", id);
            jdbc.update("UPDATE showtime_seat SET status='AVAILABLE',held_until=NULL,booking_id=NULL WHERE booking_id=? OR (showtime_id=? AND status='SOLD')", id, showtimeId);
        }
        jdbc.update("DELETE FROM loyalty_entry WHERE booking_id IN (SELECT id FROM booking WHERE user_id=?)", USER_ID);
        jdbc.update("DELETE FROM booking WHERE user_id=?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id=?", USER_ID);
    }

    @Test
    void fiveParallelIdenticalIpnsCreateTicketsOnce() throws Exception {
        Map<String, String> payload = signed();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<PaymentOutcome>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return useCase.apply(payload).outcome();
                }));
            }
            start.countDown();
            List<PaymentOutcome> outcomes = new ArrayList<>();
            for (Future<PaymentOutcome> f : results) outcomes.add(f.get(60, TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter(o -> o == PaymentOutcome.CONFIRMED).count(), outcomes.toString());
            assertEquals(THREADS - 1, outcomes.stream().filter(o -> o == PaymentOutcome.ALREADY_PROCESSED).count(), outcomes.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE booking_id=?", Integer.class, bookingId));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM booking WHERE id=?", String.class, bookingId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM booking_status_history WHERE booking_id=? AND to_status='PAID'", Integer.class, bookingId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refund_queue WHERE booking_id=?", Integer.class, bookingId));
    }

    private Map<String, String> signed() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_Amount", "20000000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_TransactionNo", "14000002");
        StringBuilder data = new StringBuilder();
        params.forEach((k, v) -> data.append(data.length() == 0 ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.US_ASCII)));
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(hashSecret.trim().getBytes(StandardCharsets.US_ASCII), "HmacSHA512"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(data.toString().getBytes(StandardCharsets.US_ASCII))) hex.append(String.format("%02x", b));
            params.put("vnp_SecureHash", hex.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new LinkedHashMap<>(params);
    }
}
