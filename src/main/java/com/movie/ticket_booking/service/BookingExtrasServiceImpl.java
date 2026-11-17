package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.BookingExtrasRequest;
import com.movie.ticket_booking.dto.booking.BookingExtrasResponse;
import com.movie.ticket_booking.dto.booking.ComboLineResponse;
import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.BookingCombo;
import com.movie.ticket_booking.entity.BookingSeat;
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
    private final BookingExtrasCalculator calculator;

    @Override
    @Transactional
    public BookingExtrasResponse update(String requesterEmail, Long bookingId, BookingExtrasRequest request) {
        // Booking row first, like payment and create-url, so this cannot interleave with a payment attempt.
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found"));
        User user = findUser(requesterEmail);
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

        bookingCombos.deleteAllForBooking(bookingId);
        bookingPromotions.deleteByBookingId(bookingId);
        List<BookingCombo> lines = calculator.persist(bookingId, user.getId(), resolved);

        long seatsTotal = seatPrices.stream().mapToLong(Long::longValue).sum();
        booking.setComboAmount(resolved.comboTotal());
        booking.setDiscountAmount(resolved.discount());
        booking.setTotalAmount(seatsTotal + resolved.comboTotal() - resolved.discount());
        bookings.save(booking);
        log.info("Booking extras updated bookingId={} combos={} discount={} total={}",
                bookingId, resolved.comboTotal(), resolved.discount(), booking.getTotalAmount());

        return new BookingExtrasResponse(bookingId, booking.getTotalAmount(), booking.getComboAmount(), booking.getDiscountAmount(),
                resolved.promotion() == null ? null : resolved.promotion().getCode(),
                lines.stream().map(l -> new ComboLineResponse(l.getId().getComboId(), l.getName(), l.getQuantity(), l.getUnitPrice())).toList());
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
