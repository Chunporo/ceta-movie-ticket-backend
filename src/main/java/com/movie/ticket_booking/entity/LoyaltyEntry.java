package com.movie.ticket_booking.entity;

import com.movie.ticket_booking.entity.enums.LoyaltyReason;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "loyalty_entry", uniqueConstraints = @UniqueConstraint(name = "uk_lentry_booking_reason", columnNames = {"booking_id", "reason"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LoyaltyReason reason;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(length = 255)
    private String note;

    @Column(nullable = false)
    private Instant createdAt;
}
