package com.movie.ticket_booking.dto.booking;

import java.util.List;

/** The full desired state: {@code combos} replaces the snacks (null or empty = none), {@code voucherCode} null = no voucher. */
public record BookingExtrasRequest(List<ComboLineRequest> combos, String voucherCode) {
}
