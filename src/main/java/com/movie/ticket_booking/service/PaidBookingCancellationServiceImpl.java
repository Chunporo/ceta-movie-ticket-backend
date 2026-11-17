package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CancelPaidBookingResponse;
import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.BookingStatusHistory;
import com.movie.ticket_booking.entity.Ticket;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.entity.enums.RefundReason;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.BookingStatusHistoryRepository;
import com.movie.ticket_booking.repository.TicketRepository;
import com.movie.ticket_booking.repository.UserRepository;
import com.movie.ticket_booking.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One transaction, booking row locked first (the order payment callbacks and the sweeper use), then the seats.
 * Seats go back on sale at once; the money is returned by an admin through the refund queue (D-13).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaidBookingCancellationServiceImpl implements PaidBookingCancellationService {
    private final UserRepository users;
    private final BookingRepository bookings;
    private final TicketRepository tickets;
    private final BookingStatusHistoryRepository history;
    private final InventoryService inventory;
    private final RefundService refunds;

    @Value("${app.booking.cancel-before:60m}")
    private Duration cancelBefore;

    @Override
    @Transactional
    public CancelPaidBookingResponse cancel(String requesterEmail, Long bookingId) {
        if (requesterEmail == null || requesterEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        User user = users.findByEmail(requesterEmail.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found"));
        if (!user.getId().equals(booking.getUser().getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not your booking");
        }
        if (booking.getStatus() != BookingStatus.PAID) {
            throw new BusinessException(HttpStatus.CONFLICT, "BOOKING_NOT_PAID",
                    "Booking is " + booking.getStatus() + ", only PAID bookings can be cancelled this way");
        }
        Instant start = booking.getShowtime().getStartTime();
        if (Instant.now().isAfter(start.minus(cancelBefore))) {
            throw new BusinessException(HttpStatus.CONFLICT, "CANCEL_WINDOW_CLOSED",
                    "Tickets can only be cancelled until " + cancelBefore.toMinutes() + " minutes before the show");
        }
        if (tickets.existsByBookingIdAndCheckedInAtIsNotNull(bookingId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "TICKET_ALREADY_USED", "A ticket of this booking was already used");
        }
        List<Long> seatIds = tickets.findByBookingIdOrderByIdAsc(bookingId).stream().map(Ticket::getSeat)
                .map(seat -> seat.getId()).toList();

        refunds.enqueue(bookingId, RefundReason.CUSTOMER_CANCEL, booking.getTotalAmount(), "Cancelled by customer");
        inventory.releaseSold(booking.getShowtime().getId(), seatIds);
        booking.setStatus(BookingStatus.REFUNDED);
        bookings.save(booking);
        history.save(BookingStatusHistory.builder().booking(booking).fromStatus(BookingStatus.PAID.name())
                .toStatus(BookingStatus.REFUNDED.name()).reason("CUSTOMER_CANCEL").build());
        log.info("Paid booking cancelled by customer bookingId={} seats={} amount={}", bookingId, seatIds.size(), booking.getTotalAmount());
        return new CancelPaidBookingResponse(bookingId, BookingStatus.REFUNDED.name(), booking.getTotalAmount());
    }
}
