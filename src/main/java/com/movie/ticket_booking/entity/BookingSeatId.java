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
public class BookingSeatId implements Serializable {
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Column(name = "seat_id", nullable = false)
    private Long seatId;
}
