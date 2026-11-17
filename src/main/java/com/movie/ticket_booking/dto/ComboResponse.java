package com.movie.ticket_booking.dto;

/** Names and descriptions come in Vietnamese plus an optional English version; the app picks by language. */
public record ComboResponse(Long id, String code, String name, String nameEn, String description, String descriptionEn,
                            String imageUrl, Long price, boolean active, int sortOrder) {
}
