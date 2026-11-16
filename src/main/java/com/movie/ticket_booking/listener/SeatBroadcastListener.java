package com.movie.ticket_booking.listener;

import com.movie.ticket_booking.dto.booking.SeatStatusBroadcast;
import com.movie.ticket_booking.entity.Seat;
import com.movie.ticket_booking.event.SeatStatusChangedEvent;
import com.movie.ticket_booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes seat changes to subscribers once the database change is durable. The broadcast is only a hint
 * (the REST seat map stays authoritative), so a failure here is logged and never affects the transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeatBroadcastListener {
    private final SimpMessagingTemplate messaging;
    private final SeatRepository seatRepository;

    /** Starts at the boot time in micros, so it keeps increasing across restarts; shared by all showtimes. */
    private final AtomicLong sequence = new AtomicLong(Instant.now().toEpochMilli() * 1000);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeatStatusChanged(SeatStatusChangedEvent event) {
        if (event.seatIds().isEmpty()) {
            return;
        }
        try {
            Map<Long, String> labels = new HashMap<>();
            for (Seat seat : seatRepository.findAllById(event.seatIds())) {
                labels.put(seat.getId(), seat.getLabel());
            }
            List<SeatStatusBroadcast.Seat> seats = event.seatIds().stream()
                    .map(id -> new SeatStatusBroadcast.Seat(id, labels.get(id), event.status().name()))
                    .toList();
            messaging.convertAndSend("/topic/showtime/" + event.showtimeId() + "/seats",
                    new SeatStatusBroadcast(sequence.incrementAndGet(), event.showtimeId(), Instant.now().toString(), seats));
        } catch (RuntimeException e) {
            log.warn("Seat broadcast failed showtimeId={} status={}", event.showtimeId(), event.status(), e);
        }
    }
}
