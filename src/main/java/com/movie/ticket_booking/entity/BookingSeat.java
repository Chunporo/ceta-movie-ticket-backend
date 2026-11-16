package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;

/** Per-seat price snapshot taken when the booking is created (D-12, D-24). */
@Entity
@Table(name = "booking_seat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSeat {
    @EmbeddedId
    private BookingSeatId id;

    @Column(nullable = false)
    private Long price;
}
