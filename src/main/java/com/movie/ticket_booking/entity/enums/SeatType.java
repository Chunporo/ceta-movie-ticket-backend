package com.movie.ticket_booking.entity.enums;

public enum SeatType {
    STANDARD,
    VIP,
    SWEETBOX,
    DELUXE,
    WHEELCHAIR,
    /** @deprecated Renamed to STANDARD. Kept for DB backward-compatibility until migration. */
    @Deprecated
    NORMAL
}
