package com.movie.ticket_booking.entity.enums;

public enum PromotionKind {
    /** Percentage off the tickets, optionally capped. */
    PERCENT_OFF,
    /** Every ticket costs a fixed price (never more than it already did). */
    FIXED_TICKET_PRICE,
    /** A perk handed out at the counter: listed in the app, not applied to an online total. */
    COUNTER_PERK
}
