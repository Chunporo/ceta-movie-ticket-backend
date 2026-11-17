package com.movie.ticket_booking.event;

import java.util.UUID;

/** The first showtime of a movie was created: tickets are on sale, so reminders can be sent. */
public record MovieOnSaleEvent(UUID movieId, String movieTitle) {
}
