package com.movie.ticket_booking.listener;

import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.TicketRepository;
import com.movie.ticket_booking.event.BookingCheckedOutEvent;
import com.movie.ticket_booking.event.ShowtimeCancelledRefundEvent;
import com.movie.ticket_booking.service.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
@RequiredArgsConstructor
public class BookingEmailListener {

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingCheckedOut(BookingCheckedOutEvent event) {
        Booking booking = bookingRepository.findById(event.bookingId()).orElse(null);
        if (booking == null) {
            return;
        }
        emailService.sendBookingConfirmation(booking, ticketRepository.findByBookingIdOrderByIdAsc(booking.getId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onShowtimeCancelled(ShowtimeCancelledRefundEvent event) {
        Booking booking = bookingRepository.findById(event.bookingId()).orElse(null);
        if (booking == null) {
            return;
        }
        try {
            emailService.sendShowtimeCancelled(booking, event.reason());
        } catch (RuntimeException ex) {
            // The refund is already committed; a mail failure must not surface as a failed cancellation.
            org.slf4j.LoggerFactory.getLogger(BookingEmailListener.class)
                    .warn("Cancellation email failed bookingId={}", booking.getId(), ex);
        }
    }
}
