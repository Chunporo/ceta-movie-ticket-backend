package com.movie.ticket_booking.entity;

import com.movie.ticket_booking.entity.enums.PromotionKind;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "promotion", uniqueConstraints = @UniqueConstraint(name = "uk_promotion_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Promotion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 100)
    private String titleEn;

    private String description;

    private String descriptionEn;

    @Column(length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PromotionKind kind;

    private Integer percentOff;

    private Long maxDiscount;

    private Long fixedPrice;

    /** Comma separated {@link java.time.DayOfWeek} names, or null for every day. */
    @Column(length = 80)
    private String weekdays;

    /** Comma separated {@link com.movie.ticket_booking.entity.enums.MovieFormat} names, or null for every format. */
    @Column(length = 40)
    private String formats;

    @Column(nullable = false)
    private boolean firstOrderOnly;

    @Column(nullable = false)
    private boolean oncePerUser;

    private Instant validFrom;

    private Instant validUntil;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
