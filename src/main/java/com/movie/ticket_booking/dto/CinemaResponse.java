package com.movie.ticket_booking.dto;

import java.math.BigDecimal;

/** {@code imax} is true when the cinema has an IMAX room. */
public record CinemaResponse(Long id, String code, String name, String city, String address,
                             BigDecimal latitude, BigDecimal longitude, boolean imax, boolean active) {
}
