package com.movie.ticket_booking.dto.booking;

import java.util.List;

/**
 * The full desired state: {@code combos} replaces the snacks (null or empty = none), {@code voucherCode} null = no voucher,
 * {@code usePoints} true spends one block of CETA points (2.000 points = 20.000d by default).
 */
public record BookingExtrasRequest(List<ComboLineRequest> combos, String voucherCode, Boolean usePoints) {
    public BookingExtrasRequest(List<ComboLineRequest> combos, String voucherCode) {
        this(combos, voucherCode, false);
    }
}
