package com.movie.ticket_booking.service.inventory;

import com.movie.ticket_booking.exception.SeatConflictException;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import com.movie.ticket_booking.repository.ShowtimeSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class InventoryServiceImpl implements InventoryService {
    private final ShowtimeRepository showtimes;
    private final ShowtimeSeatRepository seats;

    @Override
    public List<Long> claim(Long showtimeId, List<Long> seatIds, Long bookingId, Instant expiresAt) {
        if (showtimeId == null || bookingId == null || expiresAt == null || seatIds == null
                || seatIds.isEmpty() || seatIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A showtime, booking, expiry and distinct seat IDs are required");
        }
        List<Long> requested = List.copyOf(seatIds);
        showtimes.lockByIdForInventory(showtimeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));
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
        return List.of();
    }

    @Override
    public void release(Long bookingId) {
        seats.releaseHeld(bookingId, Instant.now());
    }

    @Override
    public void promoteToSold(Long bookingId) {
        List<Long> held = seats.findHeldSeatIds(bookingId);
        if (held.isEmpty()) {
            throw new SeatConflictException(List.of());
        }
        int affectedRows = seats.promoteHeld(bookingId, Instant.now());
        if (affectedRows != held.size()) {
            // Throwing rolls back any valid seats updated alongside expired holds.
            throw new SeatConflictException(held);
        }
    }

    @Override
    public boolean promoteAllToSold(Long bookingId, int expectedSeats) {
        Instant now = Instant.now();
        if (expectedSeats <= 0 || seats.countPromotable(bookingId, now) != expectedSeats) {
            return false;
        }
        int affectedRows = seats.promoteHeld(bookingId, now);
        if (affectedRows != expectedSeats) {
            throw new IllegalStateException("promoted " + affectedRows + " of " + expectedSeats + " seats for booking " + bookingId);
        }
        return true;
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
        return true;
    }

    @Override
    public int releaseExpired() {
        return seats.releaseExpiredHeld(Instant.now());
    }
}
