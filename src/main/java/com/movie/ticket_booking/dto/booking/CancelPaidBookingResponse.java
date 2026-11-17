package com.movie.ticket_booking.dto.booking;

/** The booking is REFUNDED; the money comes back through the manual refund queue (D-13), not instantly. */
public record CancelPaidBookingResponse(Long bookingId, String status, Long refundAmount) {
}
