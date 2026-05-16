package com.movie.ticket_booking.entity.enums;

public enum BookingStatus {
    HOLDING,     // Seat is temporarily reserved in Redis
    PENDING,     // User has initiated checkout
    PAID,        // Payment successful
    CANCELLED,   // User cancelled or payment failed
    EXPIRED,     // Time limit reached
    REFUNDED     // Money returned to customer
}
