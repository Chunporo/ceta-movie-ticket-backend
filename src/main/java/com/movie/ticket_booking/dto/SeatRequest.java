package com.movie.ticket_booking.dto;

import com.movie.ticket_booking.entity.enums.SeatType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatRequest {
    @NotNull
    private Integer rowNumber;

    @NotNull
    private Integer columnNumber;

    @NotBlank
    private String label;

    @NotNull
    private SeatType type;

    private boolean active = true;
}
