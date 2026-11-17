package com.movie.ticket_booking.dto.booking;

/** {@code provider}: VNPAY (the default when absent) or MOMO. */
public record CreatePaymentUrlRequest(Long bookingId, String provider) {
    public CreatePaymentUrlRequest(Long bookingId) {
        this(bookingId, null);
    }
}
