package com.movie.ticket_booking.entity;

import com.movie.ticket_booking.entity.enums.RoomType;
import com.movie.ticket_booking.entity.enums.SeatType;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pricing_policies")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    private SeatType seatType;

    private String dayOfWeek; // e.g., "MONDAY", "WEEKEND"

    @Column(precision = 10, scale = 2)
    private BigDecimal multiplier; // 1.2 for 20% increase

    @Column(precision = 10, scale = 2)
    private BigDecimal surcharge; // flat addition

    private boolean active;
}
