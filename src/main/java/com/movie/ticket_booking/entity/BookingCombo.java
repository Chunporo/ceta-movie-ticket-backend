package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;

/** A combo line of a booking, frozen when the booking is created (name and unit price included). */
@Entity
@Table(name = "booking_combo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingCombo {
    @EmbeddedId
    private BookingComboId id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private Long unitPrice;

    public long lineTotal() {
        return Math.multiplyExact(unitPrice, (long) quantity);
    }
}
