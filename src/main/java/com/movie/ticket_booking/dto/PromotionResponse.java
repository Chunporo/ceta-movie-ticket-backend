package com.movie.ticket_booking.dto;

import com.movie.ticket_booking.entity.enums.PromotionKind;

import java.time.Instant;
import java.util.List;

/** An offer as the app lists it. {@code kind} COUNTER_PERK offers are shown but cannot be applied to an online total. */
public record PromotionResponse(Long id, String code, String title, String titleEn, String description, String descriptionEn,
                                String imageUrl, PromotionKind kind, Integer percentOff, Long maxDiscount, Long fixedPrice,
                                List<String> weekdays, List<String> formats, boolean firstOrderOnly, boolean oncePerUser,
                                Instant validFrom, Instant validUntil, boolean active, int sortOrder) {
}
