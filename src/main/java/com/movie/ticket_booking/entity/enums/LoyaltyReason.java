package com.movie.ticket_booking.entity.enums;

public enum LoyaltyReason {
    /** Points earned by a PAID booking; they stop counting if it is refunded. */
    EARN,
    /** Points spent on a booking; they count only while that booking is PENDING or PAID. */
    REDEEM,
    /** A manual correction by an admin. */
    ADJUSTMENT
}
