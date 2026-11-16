package com.movie.ticket_booking.exception;

import java.util.List;

public class SeatConflictException extends RuntimeException {
    private final List<Long> conflictSeatIds;

    public SeatConflictException(List<Long> conflictSeatIds) {
        super("One or more seats are unavailable");
        this.conflictSeatIds = List.copyOf(conflictSeatIds);
    }

    public List<Long> getConflictSeatIds() {
        return conflictSeatIds;
    }
}
