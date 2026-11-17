package com.movie.ticket_booking.dto;

import java.time.Instant;

/** A promotion in the customer's wallet. */
public record VoucherResponse(PromotionResponse promotion, Instant savedAt) {
}
