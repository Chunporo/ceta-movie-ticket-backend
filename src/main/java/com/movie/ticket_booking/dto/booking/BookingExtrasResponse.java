package com.movie.ticket_booking.dto.booking;

import java.util.List;

public record BookingExtrasResponse(Long bookingId, Long totalAmount, Long comboAmount, Long discountAmount,
                                    String voucherCode, List<ComboLineResponse> combos) {
}
