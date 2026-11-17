package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** The voucher a booking uses and the discount it was given, frozen with the booking total. */
@Entity
@Table(name = "booking_promotion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingPromotion {
    @Id
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(nullable = false)
    private Long promotionId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false)
    private Long discount;

    @Column(nullable = false)
    private Instant createdAt;
}
