package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.RefundResponse;
import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.BookingStatusHistory;
import com.movie.ticket_booking.entity.RefundQueue;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.entity.enums.RefundReason;
import com.movie.ticket_booking.entity.enums.RefundStatus;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.BookingStatusHistoryRepository;
import com.movie.ticket_booking.repository.RefundQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {
    private final RefundQueueRepository refunds;
    private final BookingRepository bookings;
    private final BookingStatusHistoryRepository history;
    private final org.springframework.context.ApplicationEventPublisher events;

    @Override
    @Transactional
    public void enqueue(Long bookingId, RefundReason reason, Long amount, String note) {
        if (bookingId == null || reason == null || amount == null || amount <= 0) {
            throw new IllegalArgumentException("bookingId, reason and a positive amount are required");
        }
        // Same lock the payment path holds, so concurrent enqueues for one booking run one after another.
        bookings.findByIdForUpdate(bookingId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found"));
        if (refunds.insertIfAbsent(bookingId, reason.name(), amount, note, Instant.now()) == 1) {
            events.publishEvent(new com.movie.ticket_booking.event.RefundQueuedEvent(bookingId, reason));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundResponse> list(RefundStatus status) {
        List<RefundQueue> rows = status == null ? refunds.findAllByOrderByCreatedAtAsc()
                : refunds.findByStatusOrderByCreatedAtAsc(status);
        return rows.stream().map(RefundServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional
    public RefundResponse markDone(Long id, String note) {
        RefundQueue refund = resolvable(id);
        refund.setStatus(RefundStatus.DONE);
        refund.setDoneAt(Instant.now());
        applyNote(refund, note);
        // BASELINE section 5: EXPIRED -> REFUNDED once a late payment has been returned.
        Booking booking = bookings.findByIdForUpdate(refund.getBooking().getId()).orElse(null);
        if (booking != null && booking.getStatus() == BookingStatus.EXPIRED) {
            booking.setStatus(BookingStatus.REFUNDED);
            bookings.save(booking);
            history.save(BookingStatusHistory.builder().booking(booking).fromStatus(BookingStatus.EXPIRED.name())
                    .toStatus(BookingStatus.REFUNDED.name()).reason("REFUND_DONE").build());
        }
        events.publishEvent(new com.movie.ticket_booking.event.RefundCompletedEvent(refund.getBooking().getId()));
        log.info("Refund done id={} bookingId={}", id, refund.getBooking().getId());
        return toResponse(refunds.save(refund));
    }

    @Override
    @Transactional
    public RefundResponse markFailed(Long id, String note) {
        RefundQueue refund = resolvable(id);
        refund.setStatus(RefundStatus.FAILED);
        applyNote(refund, note);
        log.warn("Refund failed id={} bookingId={}", id, refund.getBooking().getId());
        return toResponse(refunds.save(refund));
    }

    /** PENDING -> DONE|FAILED, and FAILED -> DONE for a manual retry; DONE is final. */
    private RefundQueue resolvable(Long id) {
        RefundQueue refund = refunds.findById(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund not found"));
        if (refund.getStatus() == RefundStatus.DONE) {
            throw new BusinessException(HttpStatus.CONFLICT, "REFUND_ALREADY_DONE", "Refund is already done");
        }
        return refund;
    }

    private static void applyNote(RefundQueue refund, String note) {
        if (note != null && !note.isBlank()) {
            refund.setNote(note.length() > 500 ? note.substring(0, 500) : note);
        }
    }

    private static RefundResponse toResponse(RefundQueue r) {
        return new RefundResponse(r.getId(), r.getBooking().getId(), r.getReason().name(), r.getAmount(),
                r.getStatus().name(), r.getNote(), r.getCreatedAt(), r.getDoneAt());
    }
}
