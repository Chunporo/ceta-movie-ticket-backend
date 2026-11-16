package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "booking_status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;
    @Column(length = 20)
    private String fromStatus;
    @Column(nullable = false, length = 20)
    private String toStatus;
    @Column(length = 100)
    private String reason;
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

}
