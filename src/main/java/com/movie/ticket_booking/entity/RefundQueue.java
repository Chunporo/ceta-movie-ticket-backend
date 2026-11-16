package com.movie.ticket_booking.entity;

import com.movie.ticket_booking.entity.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "refund_queue",
        uniqueConstraints = @UniqueConstraint(name = "uk_refund_booking", columnNames = "booking_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundQueue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundReason reason;
    @Column(nullable = false)
    private Long amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RefundStatus status = RefundStatus.PENDING;
    @Column(length = 500)
    private String note;
    private Instant doneAt;
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

}
