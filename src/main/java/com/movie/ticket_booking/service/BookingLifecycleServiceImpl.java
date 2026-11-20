package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.BookingDetailResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.*;
import com.movie.ticket_booking.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** F4 (poll booking) and F5 (release seats now) from BASELINE section 6. */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookingLifecycleServiceImpl implements BookingLifecycleService {
    private final UserRepository users;
    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final BookingComboRepository bookingCombos;
    private final BookingPromotionRepository bookingPromotions;
    private final SeatRepository seats;
    private final TicketRepository tickets;
    private final BookingStatusHistoryRepository history;
    private final InventoryService inventory;
    private final RefundQueueRepository refundQueue;

    @Override
    @Transactional(readOnly = true)
    public BookingDetailResponse get(String requesterEmail, Long bookingId) {
        Booking booking = bookings.findById(bookingId).orElseThrow(BookingLifecycleServiceImpl::notFound);
        requireOwner(booking, requesterEmail);

        List<BookingSeat> snapshot = bookingSeats.findByIdBookingId(bookingId);
        Map<Long, String> labels = new HashMap<>();
        seats.findAllById(snapshot.stream().map(s -> s.getId().getSeatId()).toList())
                .forEach(seat -> labels.put(seat.getId(), seat.getLabel()));

        long remaining = booking.getStatus() == BookingStatus.PENDING
                ? Math.max(0, Duration.between(Instant.now(), booking.getHoldExpiresAt()).getSeconds()) : 0;
        // The customer is owed money while a refund row exists that no admin has paid out yet.
        boolean refundRequired = refundQueue.existsByBookingIdAndStatusNot(bookingId, com.movie.ticket_booking.entity.enums.RefundStatus.DONE);
        Showtime showtime = booking.getShowtime();
        return new BookingDetailResponse(booking.getStatus(), booking.getHoldExpiresAt(), remaining,
                booking.getTotalAmount(),
                snapshot.stream().map(s -> new BookingDetailResponse.Seat(
                        s.getId().getSeatId(), labels.get(s.getId().getSeatId()), s.getPrice())).toList(),
                tickets.findByBookingIdOrderByIdAsc(bookingId).stream().map(t -> new BookingDetailResponse.Ticket(
                        t.getId(), t.getSeat().getId(), t.getSeat().getLabel(), t.getCode(),
                        t.getPrice().longValue())).toList(),
                booking.getId(), showtime.getMovie().getTitle(), showtime.getRoom().getName(), showtime.getStartTime(),
                refundRequired, booking.getComboAmount(),
                bookingCombos.findByIdBookingId(bookingId).stream().map(c -> new com.movie.ticket_booking.dto.booking.ComboLineResponse(
                        c.getId().getComboId(), c.getName(), c.getQuantity(), c.getUnitPrice())).toList(),
                booking.getDiscountAmount(),
                bookingPromotions.findById(bookingId).map(com.movie.ticket_booking.entity.BookingPromotion::getCode).orElse(null),
                booking.getPointsAmount(), booking.getPointsUsed(), showtime.getId());
    }

    @Override
    @Transactional
    public void cancel(String requesterEmail, Long bookingId) {
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow(BookingLifecycleServiceImpl::notFound);
        requireOwner(booking, requesterEmail);
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "BOOKING_NOT_CANCELLABLE",
                    "Booking is " + booking.getStatus() + ", only PENDING bookings can be cancelled");
        }
        inventory.release(bookingId);
        booking.setStatus(BookingStatus.CANCELLED);
        bookings.saveAndFlush(booking);
        history.save(BookingStatusHistory.builder().booking(booking)
                .fromStatus(BookingStatus.PENDING.name()).toStatus(BookingStatus.CANCELLED.name())
                .reason("USER_CANCEL").build());
        log.info("Booking cancelled bookingId={}", bookingId);
    }

    private void requireOwner(Booking booking, String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        User user = users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        if (!user.getId().equals(booking.getUser().getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not your booking");
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found");
    }
}
