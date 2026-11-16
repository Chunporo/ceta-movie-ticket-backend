package com.movie.ticket_booking.service;

import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.BookingStatusHistoryRepository;
import com.movie.ticket_booking.service.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * D-06. Set-based, one transaction: lock the overdue PENDING ids, expire them with one UPDATE, write their
 * history with one INSERT...SELECT, then free every lapsed hold with one UPDATE (inside inventory).
 * Bookings are locked first because payment callbacks lock the booking before the seats; the reverse order
 * could deadlock.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookingExpiryServiceImpl implements BookingExpiryService {
    private static final int BATCH = 1000;

    private final BookingRepository bookings;
    private final BookingStatusHistoryRepository history;
    private final InventoryService inventory;

    @Value("${app.booking.hold-enabled:true}")
    private boolean holdEnabled;

    @Override
    @Transactional
    public SweepResult expireOverdue(Instant now) {
        List<Long> overdue = bookings.lockOverduePendingIds(now, BATCH);
        int expired = 0;
        if (!overdue.isEmpty()) {
            expired = bookings.expireByIds(overdue);
            history.insertForBookings(overdue, "PENDING", "EXPIRED", "HOLD_TIMEOUT", now);
        }
        int released = holdEnabled ? inventory.releaseExpired() : 0;
        if (expired > 0 || released > 0) {
            log.info("Sweeper expiredBookings={} releasedSeats={} holdEnabled={}", expired, released, holdEnabled);
        }
        return new SweepResult(expired, released);
    }
}
