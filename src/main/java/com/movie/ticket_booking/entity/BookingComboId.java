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
public class BookingComboId implements Serializable {
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "combo_id", nullable = false)
    private Long comboId;
}
