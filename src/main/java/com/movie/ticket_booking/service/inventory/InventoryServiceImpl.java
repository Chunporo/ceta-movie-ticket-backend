package com.movie.ticket_booking.service.inventory;

import com.movie.ticket_booking.entity.enums.SeatInventoryStatus;
import com.movie.ticket_booking.event.SeatStatusChangedEvent;
import com.movie.ticket_booking.exception.SeatConflictException;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import com.movie.ticket_booking.repository.ShowtimeSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Every change publishes a {@link SeatStatusChangedEvent} inside the caller's transaction; the WebSocket
 * listener only sends it once that transaction commits, so a rolled-back claim never reaches a client.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InventoryServiceImpl implements InventoryService {
    private final ShowtimeRepository showtimes;
    private final ShowtimeSeatRepository seats;
    private final ApplicationEventPublisher events;

    @Override
    public List<Long> claim(Long showtimeId, List<Long> seatIds, Long bookingId, Instant expiresAt) {
        if (showtimeId == null || bookingId == null || expiresAt == null || seatIds == null
                || seatIds.isEmpty() || seatIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A showtime, booking, expiry and distinct seat IDs are required");
        }
        List<Long> requested = List.copyOf(seatIds);
        String showtimeStatus = showtimes.lockStatusForInventory(showtimeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));
        if ("CANCELLED".equals(showtimeStatus)) {
            // Checked under the showtime lock, so a claim racing a cancellation cannot hold seats afterwards.
            throw new com.movie.ticket_booking.exception.BusinessException(HttpStatus.CONFLICT, "SHOWTIME_CANCELLED",
                    "The showtime has been cancelled");
        }
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hold expiry must be in the future");
        }
        if (seats.countAvailable(showtimeId, requested) != requested.size()) {
            Set<Long> available = new HashSet<>(seats.findAvailableSeatIds(showtimeId, requested));
            throw new SeatConflictException(requested.stream().filter(id -> !available.contains(id)).toList());
        }
        int affectedRows = seats.claimAvailable(showtimeId, requested, bookingId, expiresAt, now);
        if (affectedRows != requested.size()) {
            throw new SeatConflictException(requested);
        }
        events.publishEvent(new SeatStatusChangedEvent(showtimeId, requested, SeatInventoryStatus.HELD));
        return List.of();
    }

    @Override
    public void release(Long bookingId) {
        List<Object[]> refs = seats.lockHeldRefs(bookingId);
        seats.releaseHeld(bookingId, Instant.now());
        publish(refs, SeatInventoryStatus.AVAILABLE);
    }

    @Override
    public void promoteToSold(Long bookingId) {
        List<Long> held = seats.findHeldSeatIds(bookingId);
        if (held.isEmpty()) {
            throw new SeatConflictException(List.of());
        }
        List<Object[]> refs = seats.lockHeldRefs(bookingId);
        int affectedRows = seats.promoteHeld(bookingId, Instant.now());
        if (affectedRows != held.size()) {
            // Throwing rolls back any valid seats updated alongside expired holds.
            throw new SeatConflictException(held);
        }
        publish(refs, SeatInventoryStatus.SOLD);
    }

    @Override
    public boolean promoteAllToSold(Long bookingId, int expectedSeats) {
        Instant now = Instant.now();
        if (expectedSeats <= 0 || seats.countPromotable(bookingId, now) != expectedSeats) {
            return false;
        }
        List<Object[]> refs = seats.lockHeldRefs(bookingId);
        int affectedRows = seats.promoteHeld(bookingId, now);
        if (affectedRows != expectedSeats) {
            throw new IllegalStateException("promoted " + affectedRows + " of " + expectedSeats + " seats for booking " + bookingId);
        }
        publish(refs, SeatInventoryStatus.SOLD);
        return true;
    }

    @Override
    public int releaseExpired() {
        Instant now = Instant.now();
        List<Object[]> refs = seats.lockExpiredRefs(now);
        int released = seats.releaseExpiredHeld(now);
        publish(refs, SeatInventoryStatus.AVAILABLE);
        return released;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> unavailableSeats(Long showtimeId, List<Long> seatIds) {
        Set<Long> available = new HashSet<>(seats.findAvailableSeatIds(showtimeId, List.copyOf(seatIds)));
        return seatIds.stream().filter(id -> !available.contains(id)).toList();
    }

    @Override
    public boolean claimAndSell(Long showtimeId, List<Long> seatIds, Long bookingId) {
        if (seatIds == null || seatIds.isEmpty()) {
            return false;
        }
        showtimes.lockByIdForInventory(showtimeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));
        Instant now = Instant.now();
        if (seats.countAvailable(showtimeId, seatIds) != seatIds.size()) {
            return false;
        }
        if (seats.claimAvailable(showtimeId, seatIds, bookingId, now.plusSeconds(60), now) != seatIds.size()
                || seats.promoteHeld(bookingId, now) != seatIds.size()) {
            throw new IllegalStateException("claim-and-sell did not cover all seats for booking " + bookingId);
        }
        events.publishEvent(new SeatStatusChangedEvent(showtimeId, List.copyOf(seatIds), SeatInventoryStatus.SOLD));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, SeatInventoryStatus> seatStatuses(Long showtimeId) {
        Map<Long, SeatInventoryStatus> result = new HashMap<>();
        for (Object[] row : seats.findStatuses(showtimeId)) {
            result.put(((Number) row[0]).longValue(), SeatInventoryStatus.valueOf(String.valueOf(row[1])));
        }
        return result;
    }

    @Override
    public int initializeShowtime(Long showtimeId) {
        return seats.initializeForShowtime(showtimeId, Instant.now());
    }

    @Override
    public boolean removeShowtime(Long showtimeId) {
        showtimes.lockByIdForInventory(showtimeId);
        if (seats.countInUse(showtimeId) > 0) {
            return false;
        }
        seats.deleteForShowtime(showtimeId);
        return true;
    }

    /** One event per showtime, with that showtime's seats in id order. */
    private void publish(List<Object[]> refs, SeatInventoryStatus status) {
        Map<Long, List<Long>> byShowtime = new TreeMap<>();
        for (Object[] ref : refs) {
            byShowtime.computeIfAbsent(((Number) ref[0]).longValue(), k -> new ArrayList<>()).add(((Number) ref[1]).longValue());
        }
        byShowtime.forEach((showtimeId, seatIds) -> events.publishEvent(new SeatStatusChangedEvent(showtimeId, seatIds, status)));
    }
}
