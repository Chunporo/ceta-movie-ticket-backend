package com.movie.ticket_booking.dto;

import com.movie.ticket_booking.entity.enums.RoomType;
import jakarta.validation.constraints.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomRequest {

    @NotBlank
    @Size(max = 20)
    private String code;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotNull
    @Positive
    @Max(26)
    private Integer totalRows;

    @NotNull
    @Positive
    private Integer totalColumns;

    @Builder.Default
    private RoomType roomType = RoomType.STANDARD;

    /** Optional: defaults to the first active cinema so older clients keep working. */
    private Long cinemaId;

    private java.util.List<SeatRequest> seats;
}
