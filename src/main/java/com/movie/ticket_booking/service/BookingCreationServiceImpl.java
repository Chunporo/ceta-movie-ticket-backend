package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.CreateBookingRequest;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.entity.enums.SeatType;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.exception.SeatConflictException;
import com.movie.ticket_booking.repository.*;
import com.movie.ticket_booking.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** F2: claim seats and create the PENDING booking in one transaction (BASELINE section 6). */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookingCreationServiceImpl implements BookingCreationService {
    /** D-17: sales open 14 days before the showtime. */
    static final Duration SALE_WINDOW = Duration.ofDays(14);
    /** BASELINE section 8: at most {@code max-attempts-per-showtime-per-hour} bookings per user and showtime. */
    static final Duration ATTEMPT_WINDOW = Duration.ofHours(1);

    private final UserRepository users;
    private final ShowtimeRepository showtimes;
    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final PricingService pricing;
    private final InventoryService inventory;

    @Value("${app.booking.hold-ttl:10m}")
    private Duration holdTtl;
    @Value("${app.booking.hold-enabled:true}")
    private boolean holdEnabled;
    @Value("${app.booking.max-attempts-per-showtime-per-hour:3}")
    private int maxAttempts;
    @Value("${app.booking.max-seats-per-booking:8}")
    private int maxSeats;

    @Override
    @Transactional
    public CreateBookingResponse create(String requesterEmail, CreateBookingRequest request) {
        if (request == null || request.showtimeId() == null || request.seatIds() == null || request.seatIds().isEmpty()
                || request.seatIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.seatIds()).size() != request.seatIds().size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "showtimeId and a non-empty list of distinct seatIds are required");
        }
        if (request.seatIds().size() > maxSeats) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_SEATS", "At most " + maxSeats + " seats per booking");
        }
        User user = findUser(requesterEmail);
        Showtime showtime = showtimes.findById(request.showtimeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));

        Instant now = Instant.now();
        if (showtime.getStatus() == com.movie.ticket_booking.entity.enums.ShowtimeStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOWTIME_CANCELLED", "The showtime has been cancelled");
        }
        if (!showtime.getStartTime().isAfter(now) || showtime.getStartTime().isAfter(now.plus(SALE_WINDOW))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "OUTSIDE_SALE_WINDOW", "Showtime is not open for sale");
        }
        if (bookings.existsByUserIdAndShowtimeIdAndStatus(user.getId(), showtime.getId(), BookingStatus.PENDING)) {
            throw pendingExists();
        }

        // Hold-and-release abuse guard: every booking counts as an attempt, whatever became of it.
        if (bookings.countByUserIdAndShowtimeIdAndCreatedAtAfter(user.getId(), showtime.getId(), now.minus(ATTEMPT_WINDOW)) >= maxAttempts) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many booking attempts for this showtime, try again later");
        }

        List<Seat> selected = loadSeats(showtime, request.seatIds());
        validateCouples(selected);

        // Price is fixed here, once (D-12); never recomputed afterwards.
        Map<Long, Long> prices = new LinkedHashMap<>();
        long total = 0;
        for (Seat seat : selected) {
            long price = pricing.calculatePrice(showtime, seat).longValueExact();
            prices.put(seat.getId(), price);
            total = Math.addExact(total, price);
        }

        // The booking id is an identity column, so the row is inserted first; the claim then locks the
        // showtime row. Everything shares one transaction, so a lost claim rolls the booking back.
        Instant expiresAt = now.plus(holdTtl);
        Booking booking;
        try {
            booking = bookings.saveAndFlush(Booking.builder().user(user).showtime(showtime)
                    .status(BookingStatus.PENDING).totalAmount(total).holdExpiresAt(expiresAt).build());
        } catch (DataIntegrityViolationException e) {
            throw pendingExists(); // uk_one_pending lost a race against a concurrent request (D-02)
        }

        if (holdEnabled) {
            inventory.claim(showtime.getId(), request.seatIds(), booking.getId(), expiresAt);
        } else {
            // Kill switch (D-08): no hold. Seats are claimed when the payment arrives; here only refuse seats
            // that are already unavailable so the customer is not sent to pay for them.
            List<Long> unavailable = inventory.unavailableSeats(showtime.getId(), request.seatIds());
            if (!unavailable.isEmpty()) {
                throw new SeatConflictException(unavailable);
            }
        }

        List<BookingSeat> snapshot = selected.stream().map(seat -> BookingSeat.builder()
                .id(new BookingSeatId(booking.getId(), seat.getId())).price(prices.get(seat.getId())).build()).toList();
        bookingSeats.saveAll(snapshot);
        bookingSeats.flush();

        long snapshotSum = snapshot.stream().mapToLong(BookingSeat::getPrice).sum();
        if (snapshotSum != booking.getTotalAmount()) {
            throw new IllegalStateException("booking_seat sum " + snapshotSum + " != total " + booking.getTotalAmount());
        }
        log.info("Booking created bookingId={} showtimeId={} seats={} total={}",
                booking.getId(), showtime.getId(), request.seatIds().size(), total);

        return new CreateBookingResponse(booking.getId(), expiresAt, total, selected.stream()
                .map(seat -> new CreateBookingResponse.Seat(seat.getId(), seat.getLabel(), prices.get(seat.getId()))).toList());
    }

    private List<Seat> loadSeats(Showtime showtime, List<Long> seatIds) {
        Map<Long, Seat> byId = new HashMap<>();
        seats.findAllById(seatIds).forEach(seat -> byId.put(seat.getId(), seat));
        if (byId.size() != seatIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more seats not found");
        }
        Long roomId = showtime.getRoom().getId();
        List<Seat> ordered = seatIds.stream().map(byId::get).toList();
        if (ordered.stream().anyMatch(seat -> !Objects.equals(seat.getRoom().getId(), roomId))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Seat does not belong to the showtime room");
        }
        List<Long> inactive = ordered.stream().filter(seat -> !seat.isActive()).map(Seat::getId).toList();
        if (!inactive.isEmpty()) {
            throw new SeatConflictException(inactive);
        }
        return ordered;
    }

    /**
     * D-18: SWEETBOX is sold as a pair. Assumed pairing (the baseline does not define it):
     * columns (2k-1, 2k) in the same row, so both halves must be in the selection.
     */
    private void validateCouples(List<Seat> selected) {
        Set<String> picked = new HashSet<>();
        selected.stream().filter(s -> s.getType() == SeatType.SWEETBOX)
                .forEach(s -> picked.add(s.getRowNumber() + ":" + s.getColumnNumber()));
        for (Seat seat : selected) {
            if (seat.getType() != SeatType.SWEETBOX) continue;
            int col = seat.getColumnNumber();
            int partner = col % 2 == 1 ? col + 1 : col - 1;
            if (!picked.contains(seat.getRowNumber() + ":" + partner)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_COUPLE_SEAT",
                        "Couple seat must be booked as a pair: " + seat.getLabel());
            }
        }
    }

    private BusinessException pendingExists() {
        return new BusinessException(HttpStatus.CONFLICT, "BOOKING_ALREADY_PENDING",
                "You already have a pending booking for this showtime");
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
