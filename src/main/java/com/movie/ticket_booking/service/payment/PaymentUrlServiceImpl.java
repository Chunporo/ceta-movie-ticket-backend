package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.dto.booking.CreatePaymentUrlResponse;
import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.PaymentTransaction;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.PaymentTransactionRepository;
import com.movie.ticket_booking.repository.UserRepository;
import com.movie.ticket_booking.service.VNPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * F-pay: one {@code payment_transaction} row per attempt, {@code txn_ref = bookingId_attemptNo} (D-09).
 * The booking row is locked first so concurrent calls get distinct attempt numbers.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentUrlServiceImpl implements PaymentUrlService {
    private final UserRepository users;
    private final BookingRepository bookings;
    private final PaymentTransactionRepository payments;
    private final VNPayService vnPay;

    @Override
    @Transactional
    public CreatePaymentUrlResponse create(String requesterEmail, Long bookingId, String clientIp) {
        if (bookingId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "bookingId is required");
        }
        // Lock before any other read: InnoDB's snapshot starts at the first plain SELECT, and a snapshot
        // taken before waiting for the lock would not see the attempt the previous holder just committed.
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found"));
        User user = findUser(requesterEmail);
        if (!booking.getUser().getId().equals(user.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not your booking");
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "BOOKING_NOT_PENDING",
                    "Booking is " + booking.getStatus() + ", it cannot be paid");
        }
        if (!booking.getHoldExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "The payment window has closed");
        }

        String txnRef = bookingId + "_" + (payments.countByBookingId(bookingId) + 1);
        payments.save(PaymentTransaction.builder().booking(booking).provider(ApplyPaymentResultUseCaseImpl.PROVIDER)
                .txnRef(txnRef).amount(booking.getTotalAmount()).status("PENDING").build());
        payments.flush();

        String payUrl;
        try {
            payUrl = vnPay.buildPaymentUrl(txnRef, booking.getTotalAmount(), "Dat ve phim #" + bookingId,
                    clientIp, booking.getHoldExpiresAt());
        } catch (RuntimeException e) {
            // Rolls back the attempt row too, so a failed signing does not burn an attempt number.
            log.error("VNPay URL could not be built for bookingId={}", bookingId, e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "Could not create the payment URL");
        }
        log.info("Payment attempt created bookingId={} txnRef={}", bookingId, txnRef);
        return new CreatePaymentUrlResponse(payUrl, txnRef);
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
