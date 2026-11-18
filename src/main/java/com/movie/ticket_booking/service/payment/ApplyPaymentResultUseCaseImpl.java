package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.entity.enums.RefundReason;
import com.movie.ticket_booking.event.BookingCheckedOutEvent;
import com.movie.ticket_booking.repository.*;
import com.movie.ticket_booking.service.RefundService;
import com.movie.ticket_booking.service.VNPayService;
import com.movie.ticket_booking.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Order: signature and merchant code (pure checks) -> claim {@code processed_at} -> amount -> seats -> tickets.
 * A failed amount check rolls the claim back, so a forged or mismatched callback cannot burn the
 * idempotency flag and block the real payment.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApplyPaymentResultUseCaseImpl implements ApplyPaymentResultUseCase {
    static final String PROVIDER = "VNPAY";
    static final String MOMO = "MOMO";
    static final String VIETQR = "VIETQR";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VNPayService vnPay;
    private final org.springframework.beans.factory.ObjectProvider<com.movie.ticket_booking.service.payment.momo.MomoService> momoProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.movie.ticket_booking.service.payment.vietqr.VietQrService> vietQrProvider;
    private final PaymentTransactionRepository payments;
    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final SeatRepository seats;
    private final TicketRepository tickets;
    private final RefundService refundService;
    private final BookingStatusHistoryRepository history;
    private final InventoryService inventory;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationEventPublisher events;
    private final com.movie.ticket_booking.repository.LoyaltyEntryRepository loyaltyEntries;
    @Value("${app.loyalty.earn-vnd-per-point:1000}")
    private long earnVndPerPoint;

    @Value("${app.booking.hold-enabled:true}")
    private boolean holdEnabled;

    /** Thrown inside the transaction to roll it back and report a rejection. */
    private static final class Rejected extends RuntimeException {
        private final PaymentResult result;

        Rejected(PaymentResult result) {
            super(result.outcome().name(), null, false, false);
            this.result = result;
        }
    }

    @Override
    public PaymentResult apply(Map<String, String> params) {
        String txnRef = params.get("vnp_TxnRef");
        if (!vnPay.verifyCallback(params)) {
            log.warn("SECURITY VNPay callback with invalid signature txnRef={}", txnRef);
            return new PaymentResult(PaymentOutcome.INVALID_SIGNATURE, null);
        }
        if (!vnPay.isOwnTmnCode(params.get("vnp_TmnCode"))) {
            log.warn("SECURITY VNPay callback with foreign vnp_TmnCode txnRef={}", txnRef);
            return new PaymentResult(PaymentOutcome.INVALID_MERCHANT, null);
        }
        long paid = parseAmount(params.get("vnp_Amount"));
        boolean success = "00".equals(params.get("vnp_ResponseCode"))
                && (params.get("vnp_TransactionStatus") == null || "00".equals(params.get("vnp_TransactionStatus")));
        return settle(PROVIDER, txnRef, paid, success, params.get("vnp_TransactionNo"));
    }

    @Override
    public PaymentResult applyMomo(Map<String, String> params) {
        String txnRef = params.get("orderId");
        var momo = momoProvider.getIfAvailable();
        if (momo == null || !momo.verifyCallback(params)) {
            log.warn("SECURITY MoMo callback with invalid signature orderId={}", txnRef);
            return new PaymentResult(PaymentOutcome.INVALID_SIGNATURE, null);
        }
        if (!momo.isOwnPartner(params.get("partnerCode"))) {
            log.warn("SECURITY MoMo callback with foreign partnerCode orderId={}", txnRef);
            return new PaymentResult(PaymentOutcome.INVALID_MERCHANT, null);
        }
        long paid;
        try {
            paid = Long.parseLong(params.get("amount")); // MoMo amounts are whole dong
        } catch (NumberFormatException e) {
            paid = -1;
        }
        return settle(MOMO, txnRef, paid, "0".equals(params.get("resultCode")), params.get("transId"));
    }

    @Override
    public PaymentResult applyVietQr(Map<String, String> payload) {
        var vietQr = vietQrProvider.getIfAvailable();
        if (vietQr == null || !vietQr.enabled()) {
            return new PaymentResult(PaymentOutcome.INVALID_SIGNATURE, null);
        }
        // Money leaving the account, or going to some other account, is not a payment.
        if (!"in".equalsIgnoreCase(payload.getOrDefault("transferType", "in")) || !vietQr.isOwnAccount(payload.get("accountNumber"))) {
            return new PaymentResult(PaymentOutcome.INVALID_MERCHANT, null);
        }
        String txnRef = com.movie.ticket_booking.service.payment.vietqr.VietQrService.txnRefIn(payload);
        long paid;
        try {
            paid = new java.math.BigDecimal(payload.get("transferAmount")).longValueExact();
        } catch (RuntimeException e) {
            paid = -1;
        }
        String reference = payload.getOrDefault("referenceCode", payload.get("id"));
        return settle(VIETQR, txnRef, paid, true, reference);
    }

    /** What both providers share once their own signature and merchant checks have passed. */
    private PaymentResult settle(String provider, String txnRef, long paid, boolean success, String providerTxn) {
        if (txnRef == null || txnRef.isBlank()) {
            return new PaymentResult(PaymentOutcome.ORDER_NOT_FOUND, null);
        }
        try {
            return new TransactionTemplate(transactionManager).execute(status -> applyInTransaction(provider, txnRef, paid, success, providerTxn));
        } catch (Rejected rejected) {
            return rejected.result;
        }
    }

    private PaymentResult applyInTransaction(String provider, String txnRef, long paid, boolean success, String providerTxn) {
        Instant now = Instant.now();
        if (payments.markProcessed(provider, txnRef, now) == 0) {
            PaymentTransaction known = payments.findByProviderAndTxnRef(provider, txnRef).orElse(null);
            return known == null
                    ? new PaymentResult(PaymentOutcome.ORDER_NOT_FOUND, null)
                    : new PaymentResult(PaymentOutcome.ALREADY_PROCESSED, known.getBooking().getId());
        }
        PaymentTransaction payment = payments.findByProviderAndTxnRef(provider, txnRef).orElseThrow();
        Long bookingId = payment.getBooking().getId();
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow();

        if (paid != booking.getTotalAmount() || paid != payment.getAmount()) {
            log.warn("SECURITY {} amount mismatch txnRef={} paid={} booking={} expected={}",
                    provider, txnRef, paid, bookingId, booking.getTotalAmount());
            throw new Rejected(new PaymentResult(PaymentOutcome.INVALID_AMOUNT, bookingId));
        }

        payment.setProviderTxn(providerTxn);
        if (!success) {
            payment.setStatus("FAILED");
            payments.save(payment);
            return new PaymentResult(PaymentOutcome.PAYMENT_FAILED, bookingId);
        }

        if (booking.getStatus() != BookingStatus.PENDING) {
            RefundReason reason = booking.getStatus() == BookingStatus.PAID ? RefundReason.DOUBLE_PAY : RefundReason.LATE_PAYMENT;
            return queueRefund(payment, booking, reason, "Booking was " + booking.getStatus() + " when payment arrived");
        }

        List<BookingSeat> snapshot = bookingSeats.findByIdBookingId(bookingId);
        if (!seatsSold(booking, snapshot)) {
            inventory.release(bookingId);
            booking.setStatus(BookingStatus.EXPIRED);
            bookings.save(booking);
            recordHistory(booking, BookingStatus.PENDING, BookingStatus.EXPIRED, "PAYMENT_AFTER_HOLD_LOST");
            return queueRefund(payment, booking, RefundReason.LATE_PAYMENT, "Seats no longer held when payment arrived");
        }

        long ticketTotal = 0;
        for (BookingSeat line : snapshot) {
            ticketTotal += line.getPrice();
            tickets.save(Ticket.builder().booking(booking).showtime(booking.getShowtime())
                    .seat(seats.getReferenceById(line.getId().getSeatId()))
                    .price(BigDecimal.valueOf(line.getPrice())).code(ticketCode()).build());
        }
        // Invariant: tickets + combos - voucher (- points, B5) == the amount VNPay was asked for.
        if (ticketTotal + booking.getComboAmount() - booking.getDiscountAmount() - booking.getPointsAmount() != booking.getTotalAmount()) {
            throw new IllegalStateException("ticket sum " + ticketTotal + " + combos " + booking.getComboAmount() + " - voucher " + booking.getDiscountAmount() + " - points " + booking.getPointsAmount()
                    + " != booking total " + booking.getTotalAmount());
        }
        tickets.flush();
        booking.setStatus(BookingStatus.PAID);
        booking.setPaidAt(now);
        bookings.save(booking);
        payment.setStatus("SUCCESS");
        payments.save(payment);
        recordHistory(booking, BookingStatus.PENDING, BookingStatus.PAID, provider + "_PAID");
        earnPoints(booking);
        events.publishEvent(new BookingCheckedOutEvent(bookingId)); // confirmation email, sent after commit
        log.info("Payment applied bookingId={} txnRef={} tickets={}", bookingId, txnRef, snapshot.size());
        return new PaymentResult(PaymentOutcome.CONFIRMED, bookingId);
    }

    /** Hold mode: HELD -> SOLD. Kill switch: also try AVAILABLE -> SOLD (claim-at-IPN), same use case otherwise. */
    private boolean seatsSold(Booking booking, List<BookingSeat> snapshot) {
        if (inventory.promoteAllToSold(booking.getId(), snapshot.size())) {
            return true;
        }
        if (holdEnabled) {
            return false;
        }
        return inventory.claimAndSell(booking.getShowtime().getId(),
                snapshot.stream().map(line -> line.getId().getSeatId()).toList(), booking.getId());
    }

    private PaymentResult queueRefund(PaymentTransaction payment, Booking booking, RefundReason reason, String note) {
        payment.setStatus("REFUND_QUEUED");
        payments.save(payment);
        refundService.enqueue(booking.getId(), reason, payment.getAmount(), note);
        log.warn("Refund queued bookingId={} reason={} txnRef={}", booking.getId(), reason, payment.getTxnRef());
        return new PaymentResult(PaymentOutcome.REFUND_QUEUED, booking.getId());
    }

    private void recordHistory(Booking booking, BookingStatus from, BookingStatus to, String reason) {
        history.save(BookingStatusHistory.builder().booking(booking)
                .fromStatus(from.name()).toStatus(to.name()).reason(reason).build());
    }

    /** vnp_Amount is VND x 100; anything unparsable or not a whole number of dong never matches. */
    private static long parseAmount(String vnpAmount) {
        try {
            long raw = Long.parseLong(vnpAmount);
            return raw % 100 == 0 ? raw / 100 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String ticketCode() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return "TCK-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** One CETA point per {@code earn-vnd-per-point} of cash actually paid; the unique (booking, reason) key makes a replay harmless. */
    private void earnPoints(com.movie.ticket_booking.entity.Booking booking) {
        long points = booking.getTotalAmount() / earnVndPerPoint;
        if (points <= 0 || loyaltyEntries.existsByBookingIdAndReason(booking.getId(), com.movie.ticket_booking.entity.enums.LoyaltyReason.EARN)) {
            return;
        }
        loyaltyEntries.save(com.movie.ticket_booking.entity.LoyaltyEntry.builder().userId(booking.getUser().getId())
                .delta((int) points).reason(com.movie.ticket_booking.entity.enums.LoyaltyReason.EARN)
                .bookingId(booking.getId()).createdAt(Instant.now()).build());
    }
}
