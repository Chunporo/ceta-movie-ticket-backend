package com.movie.ticket_booking.dto.booking;

/** A combo line as bought: name and unit price are the ones frozen at booking time. */
public record ComboLineResponse(Long comboId, String name, int quantity, Long unitPrice) {
}
