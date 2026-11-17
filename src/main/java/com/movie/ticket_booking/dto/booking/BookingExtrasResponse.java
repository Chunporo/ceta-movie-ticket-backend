package com.movie.ticket_booking.dto.booking;

import java.util.List;

/** {@code totalAmount} = seats + combos - discount - pointsAmount. */
public record BookingExtrasResponse(Long bookingId, Long totalAmount, Long comboAmount, Long discountAmount,
                                    String voucherCode, List<ComboLineResponse> combos,
                                    Long pointsAmount, Integer pointsUsed) {
    public BookingExtrasResponse(Long bookingId, Long totalAmount, Long comboAmount, Long discountAmount,
                                 String voucherCode, List<ComboLineResponse> combos) {
        this(bookingId, totalAmount, comboAmount, discountAmount, voucherCode, combos, 0L, 0);
    }
}
