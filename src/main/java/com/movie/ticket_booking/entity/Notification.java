package com.movie.ticket_booking.entity;

import com.movie.ticket_booking.entity.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notification",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_once", columnNames = {"user_id", "type", "booking_id"}),
        indexes = @Index(name = "idx_notification_user", columnList = "user_id, id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant readAt;
}
