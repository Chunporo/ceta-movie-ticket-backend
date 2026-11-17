package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.BookingExtrasRequest;
import com.movie.ticket_booking.dto.booking.BookingExtrasResponse;
import com.movie.ticket_booking.dto.booking.ComboLineResponse;
import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.BookingCombo;
import com.movie.ticket_booking.entity.BookingSeat;
import com.movie.ticket_booking.entity.LoyaltyEntry;
import com.movie.ticket_booking.entity.enums.LoyaltyReason;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingExtrasServiceImpl implements BookingExtrasService {
    private final UserRepository users;
    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final BookingComboRepository bookingCombos;
    private final BookingPromotionRepository bookingPromotions;
    private final PaymentTransactionRepository payments;
    private final LoyaltyEntryRepository loyaltyEntries;
    private final BookingExtrasCalculator calculator;

    @Override
    @Transactional
    public BookingExtrasResponse update(String requesterEmail, Long bookingId, BookingExtrasRequest request) {
        // Customer row first (same order as account deletion; it serialises spending points), then the booking row,
        // so this cannot interleave with a payment attempt. Both are locking reads: no stale snapshot after the waits.
        User user = findUserForUpdate(requesterEmail);
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found"));
        if (!booking.getUser().getId().equals(user.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not your booking");
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "BOOKING_NOT_EDITABLE",
                    "Booking is " + booking.getStatus() + ", its extras can no longer change");
        }
        if (!booking.getHoldExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "The payment window has closed");
        }
        if (payments.countByBookingId(bookingId) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_STARTED",
                    "Payment has started for this booking, so the total is final");
        }

        List<BookingSeat> snapshot = bookingSeats.findByIdBookingId(bookingId);
        List<Long> seatPrices = snapshot.stream().map(BookingSeat::getPrice).toList();
        BookingExtrasCalculator.Resolved resolved = calculator.resolve(user.getId(), booking.getShowtime(), seatPrices, bookingId,
                request == null ? null : request.combos(), request == null ? null : request.voucherCode());

        long seatsTotal = seatPrices.stream().mapToLong(Long::longValue).sum();
        long payable = seatsTotal + resolved.comboTotal() - resolved.discount();

        bookingCombos.deleteAllForBooking(bookingId);
        bookingPromotions.deleteByBookingId(bookingId);
        List<BookingCombo> lines = calculator.persist(bookingId, user.getId(), resolved);

        int pointsUsed = 0;
        long pointsAmount = 0;
        loyaltyEntries.deleteRedeemForBooking(bookingId);
        if (request != null && Boolean.TRUE.equals(request.usePoints())) {
            pointsUsed = calculator.redeemBlockPoints();
            pointsAmount = calculator.redeemBlockVnd();
            spendPoints(user.getId(), bookingId, pointsUsed, pointsAmount, payable);
        }

        booking.setComboAmount(resolved.comboTotal());
        booking.setDiscountAmount(resolved.discount());
        booking.setPointsUsed(pointsUsed);
        booking.setPointsAmount(pointsAmount);
        booking.setTotalAmount(payable - pointsAmount);
        bookings.save(booking);
        log.info("Booking extras updated bookingId={} combos={} discount={} points={} total={}",
                bookingId, resolved.comboTotal(), resolved.discount(), pointsUsed, booking.getTotalAmount());

        return new BookingExtrasResponse(bookingId, booking.getTotalAmount(), booking.getComboAmount(), booking.getDiscountAmount(),
                resolved.promotion() == null ? null : resolved.promotion().getCode(),
                lines.stream().map(l -> new ComboLineResponse(l.getId().getComboId(), l.getName(), l.getQuantity(), l.getUnitPrice())).toList(),
                booking.getPointsAmount(), booking.getPointsUsed());
    }

    /**
     * Reserves one block of points for this booking. The customer row is already locked (see update), so two bookings cannot
     * spend the same points; they come back by themselves if the booking ends unpaid (see LoyaltyEntryRepository).
     */
    private void spendPoints(Long userId, Long bookingId, int points, long worthVnd, long payable) {
        if (payable < worthVnd) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "POINTS_NOT_APPLICABLE",
                    "The order is too small to spend " + points + " points");
        }
        if (loyaltyEntries.balance(userId, bookingId) < points) {
            throw new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_POINTS", "Not enough CETA points");
        }
        loyaltyEntries.save(LoyaltyEntry.builder().userId(userId).delta(-points).reason(LoyaltyReason.REDEEM)
                .bookingId(bookingId).createdAt(Instant.now()).build());
    }

    private User findUserForUpdate(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmailForUpdate(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
