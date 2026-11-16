package com.movie.ticket_booking.dto.booking;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
}
