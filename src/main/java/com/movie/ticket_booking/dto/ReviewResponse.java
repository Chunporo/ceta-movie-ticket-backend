package com.movie.ticket_booking.dto;

import java.time.Instant;

/** {@code author} is masked (first letters only) because reviews are public. */
public record ReviewResponse(Long id, int rating, String comment, String author, boolean mine, Instant createdAt, Instant updatedAt) {
}
