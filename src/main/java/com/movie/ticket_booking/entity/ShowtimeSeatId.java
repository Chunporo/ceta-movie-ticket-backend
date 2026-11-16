package com.movie.ticket_booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ShowtimeSeatId implements Serializable {
    @Column(name = "showtime_id")
    private Long showtimeId;
    @Column(name = "seat_id")
    private Long seatId;
}
