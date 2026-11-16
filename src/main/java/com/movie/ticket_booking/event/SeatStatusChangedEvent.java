package com.movie.ticket_booking.event;

import com.movie.ticket_booking.entity.enums.SeatInventoryStatus;

import java.util.List;

/** Published by the inventory module inside the transaction that changed the seats; delivered after commit. */
public record SeatStatusChangedEvent(Long showtimeId, List<Long> seatIds, SeatInventoryStatus status) {
}
