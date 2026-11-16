package com.movie.ticket_booking.service.payment;

public record PaymentResult(PaymentOutcome outcome, Long bookingId) {
}
