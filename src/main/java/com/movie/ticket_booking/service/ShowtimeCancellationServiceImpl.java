package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CancelShowtimeResponse;
import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.BookingStatusHistory;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.entity.enums.RefundReason;
import com.movie.ticket_booking.event.ShowtimeCancelledRefundEvent;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.BookingStatusHistoryRepository;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import com.movie.ticket_booking.service.inventory.InventoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Three steps, each its own transaction, so the lock order stays "booking, then seats" (a booking claiming seats
 * holds its own row and then the showtime row; taking the showtime first here would deadlock with it):
 * <ol>
 *   <li>flip the showtime to CANCELLED (one UPDATE). From its commit on, {@code InventoryService.claim} refuses it;</li>
 *   <li>per booking: lock it, then PENDING -> CANCELLED (hold released) or PAID -> REFUNDED + refund_queue;</li>
 *   <li>a second pass for bookings that raced step 1 (their claim committed before the flip).</li>
 * </ol>
 * A failure part-way leaves a CANCELLED showtime with some bookings untouched; calling cancel again resumes.
 */
@Service
@Slf4j
public class ShowtimeCancellationServiceImpl implements ShowtimeCancellationService {
    private static final Set<BookingStatus> OPEN = Set.of(BookingStatus.PENDING, BookingStatus.PAID);

    private final ShowtimeRepository showtimes;
    private final BookingRepository bookings;
    private final BookingStatusHistoryRepository history;
    private final InventoryService inventory;
    private final RefundService refunds;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public ShowtimeCancellationServiceImpl(ShowtimeRepository showtimes, BookingRepository bookings,
                                           BookingStatusHistoryRepository history, InventoryService inventory,
                                           RefundService refunds, ApplicationEventPublisher events,
                                           PlatformTransactionManager txManager) {
        this.showtimes = showtimes;
        this.bookings = bookings;
        this.history = history;
        this.inventory = inventory;
        this.refunds = refunds;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public CancelShowtimeResponse cancel(Long showtimeId, String reason, String adminEmail) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A cancellation reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 500) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Reason is at most 500 characters");
        }
        if (!showtimes.existsById(showtimeId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND", "Showtime not found");
        }
        Integer flipped = tx.execute(s -> showtimes.markCancelled(showtimeId, trimmed, Instant.now()));
        log.warn("Showtime cancel showtimeId={} by={} firstCall={} reason={}", showtimeId, adminEmail, flipped != null && flipped == 1, trimmed);

        int refunded = 0;
        int released = 0;
        // Pass 2 repeats pass 1's query: it picks up claims that were committed just before the flip.
        for (int pass = 0; pass < 2; pass++) {
            List<Long> ids = bookings.findIdsByShowtimeIdAndStatusIn(showtimeId, OPEN);
            for (Long id : ids) {
                BookingStatus outcome = tx.execute(s -> handle(id, trimmed));
                if (outcome == BookingStatus.REFUNDED) {
                    refunded++;
                } else if (outcome == BookingStatus.CANCELLED) {
                    released++;
                }
            }
        }
        log.warn("Showtime cancelled showtimeId={} refunded={} released={}", showtimeId, refunded, released);
        return new CancelShowtimeResponse(showtimeId, "CANCELLED", refunded, released);
    }

    /** Runs inside the per-booking transaction; returns the booking's new status, or null if nothing changed. */
    private BookingStatus handle(Long bookingId, String reason) {
        Booking booking = bookings.findByIdForUpdate(bookingId).orElse(null);
        if (booking == null) {
            return null;
        }
        BookingStatus from = booking.getStatus();
        if (from == BookingStatus.PENDING) {
            inventory.release(bookingId);
            booking.setStatus(BookingStatus.CANCELLED);
            bookings.save(booking);
            record(booking, from, BookingStatus.CANCELLED);
            return BookingStatus.CANCELLED;
        }
        if (from == BookingStatus.PAID) {
            // Same booking lock the payment path takes; enqueue is idempotent per booking.
            refunds.enqueue(bookingId, RefundReason.SHOWTIME_CANCELLED, booking.getTotalAmount(), "Showtime cancelled: " + reason);
            booking.setStatus(BookingStatus.REFUNDED);
            bookings.save(booking);
            record(booking, from, BookingStatus.REFUNDED);
            events.publishEvent(new ShowtimeCancelledRefundEvent(bookingId, reason));
            return BookingStatus.REFUNDED;
        }
        return null;
    }

    private void record(Booking booking, BookingStatus from, BookingStatus to) {
        history.save(BookingStatusHistory.builder().booking(booking).fromStatus(from.name()).toStatus(to.name())
                .reason("SHOWTIME_CANCELLED").build());
    }
}
