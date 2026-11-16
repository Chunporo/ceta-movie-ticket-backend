package com.movie.ticket_booking.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class BookingSweeper {
    private final BookingExpiryService expiry;

    /** Failures propagate to Spring's scheduler, which logs them and keeps the schedule running. */
    @Scheduled(fixedDelayString = "${app.booking.sweeper-interval:20s}", initialDelayString = "${app.booking.sweeper-interval:20s}")
    public void sweep() {
        expiry.expireOverdue(Instant.now());
    }
}
